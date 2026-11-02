package io.github.ilyaslabs.foodstack.rider.impl;

import java.util.List;
import java.util.Set;

import io.github.ilyaslabs.foodstack.rider.api.RiderCandidate;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderSearch;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderDetails;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class RiderApiImplTest {

    private final RiderService riders = mock(RiderService.class);

    private final RiderActivity activity = mock(RiderActivity.class);

    private final RiderApiImpl api = new RiderApiImpl(this.riders, this.activity);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("finding riders is for the platform and dispatch staff; reading one follows the screens' rules")
    void access() {
        RiderSearch search = new RiderSearch(31.5, 74.3, 5000, 3, Set.of());
        given(this.activity.findAvailable(search)).willReturn(List.of(new RiderCandidate("r1", "Ali", Vehicle.CAR, 10, 5, 90)));
        given(this.riders.get("r1")).willReturn(new RiderDetails("r1", RiderKind.STORE, "s1", "Ali", null, Vehicle.CAR, null,
                null, RiderStatus.ACTIVE, false, null, null, null, List.of(), 100, 0, 0, 0));

        assertThatThrownBy(() -> this.api.findAvailable(search)).isInstanceOf(ModuleException.Unauthorized.class);
        caller("owner", "store:portal");
        assertThatThrownBy(() -> this.api.findAvailable(search)).isInstanceOf(ModuleException.Forbidden.class);
        assertThat(this.api.getRider("r1").storeId()).isEqualTo("s1");
        caller("support", "dispatch:manage");
        assertThat(this.api.findAvailable(search)).extracting(RiderCandidate::riderId).containsExactly("r1");
        caller("platform", "module:service");
        assertThat(this.api.findAvailable(search)).hasSize(1);
    }

    private static void caller(String subject, String... permissions) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions)).build();
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt));
    }
}
