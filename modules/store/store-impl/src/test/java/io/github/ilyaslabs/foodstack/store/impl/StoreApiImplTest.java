package io.github.ilyaslabs.foodstack.store.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.core.application.StoreListingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class StoreApiImplTest {

    private final StoreListingService service = mock(StoreListingService.class);

    private final StoreApiImpl api = new StoreApiImpl(this.service);

    @org.junit.jupiter.api.AfterEach
    void signOut() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    /** Signs in a caller with these permissions, as the forwarded token would. */
    private static void callerWith(String subject, String... permissions) {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "ES256")
            .subject(subject)
            .claim("perms", java.util.List.of(permissions))
            .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter().convert(jwt));
    }

    @Test
    @DisplayName("delegates listings and id pages to the core listing service")
    void delegates() {
        StoreListingView listing = mock(StoreListingView.class);
        StoreIdPage page = new StoreIdPage(List.of("s1", "s2"), "s2");
        given(this.service.listing("s1")).willReturn(listing);
        given(this.service.storeIds(null, 2)).willReturn(page);
        callerWith("foodstack-service", "module:service");

        assertThat(this.api.getListing("s1")).isEqualTo(listing);
        assertThat(this.api.listStoreIds(null, 2)).isEqualTo(page);
    }

    @Test
    @DisplayName("listings include branches that are not live, so customers and store owners may not read them (TDD 9.1)")
    void permissions() {
        callerWith("customer");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.api.getListing("s1"))
            .isInstanceOf(io.github.ilyaslabs.moduleswitch.ModuleException.Forbidden.class);
        callerWith("owner", "store:portal");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.api.listStoreIds(null, 2))
            .isInstanceOf(io.github.ilyaslabs.moduleswitch.ModuleException.Forbidden.class);
        callerWith("admin", "search:manage");
        this.api.listStoreIds(null, 2);
    }
}
