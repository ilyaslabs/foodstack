package io.github.ilyaslabs.foodstack.identity.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * The authorization server endpoints ({@code /oauth2/**}, {@code /.well-known/**},
 * {@code /userinfo}, {@code /connect/logout}) and the login page, ahead of the API chain.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class IdentityWebSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain identityAuthorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
            .with(authorizationServer, (server) -> server.oidc(Customizer.withDefaults()))
            .authorizeHttpRequests((authorize) -> authorize.anyRequest().authenticated())
            .exceptionHandling((exceptions) -> exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
            .oauth2ResourceServer((resourceServer) -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain identityLoginFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/login", "/login/**", "/logout")
            .authorizeHttpRequests((authorize) -> authorize.anyRequest().permitAll())
            .formLogin((form) -> form.loginPage("/login").permitAll())
            .logout((logout) -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout"));
        return http.build();
    }
}
