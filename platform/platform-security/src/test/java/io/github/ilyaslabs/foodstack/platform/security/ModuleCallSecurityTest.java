package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.moduleswitch.ModuleException;
import io.github.ilyaslabs.moduleswitch.core.ModuleCallContexts;
import io.github.ilyaslabs.moduleswitch.core.ModuleCallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ModuleCallSecurityTest {

    /** Stands in for a module API interface (the package of this test is not a @ModuleApi package). */
    interface Greeter {

        String whoAmI();
    }

    private final JwtDecoder decoder = mock(JwtDecoder.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("background work runs with the service token as authentication and as the forwarded header, then restores")
    void serviceCalls() {
        given(this.decoder.decode("svc-token")).willReturn(jwt("foodstack-service", ModuleAccess.SERVICE));
        ServiceCalls calls = new ServiceCalls(() -> "svc-token", this.decoder);

        String seen = calls.call(() -> {
            ModuleAccess.requireServiceOr("anything:else");
            var request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            return CurrentUser.id() + " " + request.getHeader("authorization");
        });

        assertThat(seen).isEqualTo("foodstack-service Bearer svc-token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    @DisplayName("work already running for a user keeps that user")
    void keepsUser() {
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt("u1")));
        ServiceCalls calls = new ServiceCalls(() -> {
            throw new AssertionError("no service token needed");
        }, this.decoder);

        assertThat(calls.call(CurrentUser::id)).isEqualTo("u1");
    }

    @Test
    @DisplayName("a call that arrived over HTTP runs as the forwarded token's user; a bad token is a 401")
    void remoteCalls() {
        given(this.decoder.decode("user-token")).willReturn(jwt("u7", "store:manage"));
        given(this.decoder.decode("expired")).willThrow(new JwtException("expired"));
        ObjectProvider<JwtDecoder> provider = mock();
        given(provider.getObject()).willReturn(this.decoder);
        Greeter target = () -> CurrentUser.id() + (CurrentUser.hasAuthority("store:manage") ? " manager" : "");
        Greeter api = (Greeter) new ModuleCallAuthentication(provider).wrap(target, new Class<?>[] { Greeter.class });

        String remote = ModuleCallContexts.asRemoteCall("store", Map.of("Authorization", "Bearer user-token"), api::whoAmI);

        assertThat(remote).isEqualTo("u7 manager");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThatThrownBy(() -> ModuleCallContexts.asRemoteCall("store", Map.of("Authorization", "Bearer expired"), api::whoAmI))
            .isInstanceOf(ModuleException.Unauthorized.class);
        assertThatThrownBy(api::whoAmI).isInstanceOf(ModuleException.Unauthorized.class);
        assertThat(ModuleCallContext.current()).isEmpty();
    }

    private static Jwt jwt(String subject, String... permissions) {
        return Jwt.withTokenValue("t")
            .header("alg", "ES256")
            .subject(subject)
            .issuedAt(Instant.now())
            .claim(TokenClaims.PERMISSIONS, List.of(permissions))
            .build();
    }
}
