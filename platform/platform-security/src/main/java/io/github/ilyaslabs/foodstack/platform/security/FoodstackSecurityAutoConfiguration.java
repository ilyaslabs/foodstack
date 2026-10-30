package io.github.ilyaslabs.foodstack.platform.security;


import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Every module validates tokens itself, in memory (TDD 9.1). Where {@code identity} is local the
 * signing keys are read straight from it; elsewhere they are fetched once from its JWKS endpoint
 * and cached. Revoked token versions are checked against Redis.
 */
@AutoConfiguration(after = { DataRedisAutoConfiguration.class, FoodstackSecurityCoreAutoConfiguration.class }, beforeName = {
        "org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet.OAuth2AuthorizationServerJwtAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration" })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(FoodstackSecurityProperties.class)
@Import(ApiSecurityConfiguration.class)
public final class FoodstackSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(FoodstackSecurityProperties properties, ObjectProvider<JWKSource<SecurityContext>> localKeys,
            ObjectProvider<TokenRevocations> revocations) {
        JWKSource<SecurityContext> keys = localKeys.getIfAvailable();
        NimbusJwtDecoder decoder = (keys != null)
                ? NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithm(SignatureAlgorithm.ES256).build()
                : NimbusJwtDecoder.withJwkSetUri(properties.effectiveJwkSetUri()).jwsAlgorithm(SignatureAlgorithm.ES256).build();
        TokenRevocations tokenRevocations = revocations.getIfAvailable();
        decoder.setJwtValidator((tokenRevocations != null)
                ? new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
                        new TokenVersionValidator(tokenRevocations))
                : JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

}
