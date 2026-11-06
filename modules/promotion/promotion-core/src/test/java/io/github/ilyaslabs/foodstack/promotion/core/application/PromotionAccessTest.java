package io.github.ilyaslabs.foodstack.promotion.core.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.platform.security.BranchBrands;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PromotionAccessTest {

    private final BrandRules rules = mock(BrandRules.class);

    private final PromotionAccess access = new PromotionAccess(this.rules);

    @BeforeEach
    void branches() {
        BranchBrands map = mock(BranchBrands.class);
        given(map.brandOf("gulberg")).willReturn(Optional.of("b1"));
        given(map.brandOf("dha")).willReturn(Optional.of("b1"));
        given(map.brandOf("other")).willReturn(Optional.of("b2"));
        CurrentUser.useBranchBrands(map);
    }

    @AfterEach
    void clear() {
        CurrentUser.useBranchBrands(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a brand owner targets all or chosen branches of their brand, never another brand's")
    void brandOwner() {
        signIn(List.of("b1"), List.of());
        assertThatCode(() -> this.access.requireCreate("b1", List.of())).doesNotThrowAnyException();
        assertThatCode(() -> this.access.requireCreate("b1", List.of("gulberg", "dha"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> this.access.requireCreate("b1", List.of("other")))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("PROMOTION_INVALID"));
        assertThatThrownBy(() -> this.access.requireCreate(null, List.of()))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("PROMOTION_ACCESS_DENIED"));
        assertThatThrownBy(() -> this.access.requireCreate("b2", List.of()))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("BRAND_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("a branch manager makes promotions for their own branch only, and only while the brand owner allows it (PRD BB-11)")
    void branchManager() {
        signIn(List.of(), List.of("gulberg"));
        assertThatThrownBy(() -> this.access.requireCreate("b1", List.of("gulberg")))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("BRANCH_PROMOTIONS_OFF"));

        given(this.rules.branchPromotionsAllowed("b1")).willReturn(true);
        assertThatCode(() -> this.access.requireCreate("b1", List.of("gulberg"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> this.access.requireCreate("b1", List.of()))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("PROMOTION_ACCESS_DENIED"));
        assertThatThrownBy(() -> this.access.requireCreate("b1", List.of("gulberg", "dha")))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("PROMOTION_ACCESS_DENIED"));

        // sees the brand-wide promotion and their own, but changes only their own
        PromotionDetails brandWide = details(List.of());
        PromotionDetails dhaOnly = details(List.of("dha"));
        assertThatCode(() -> this.access.requireView(brandWide)).doesNotThrowAnyException();
        assertThatThrownBy(() -> this.access.requireView(dhaOnly)).isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.access.requireEdit(brandWide, List.of("gulberg"))).isInstanceOf(ModuleException.Forbidden.class);
        assertThatCode(() -> this.access.requireEdit(details(List.of("gulberg")), List.of("gulberg"))).doesNotThrowAnyException();

        assertThat(this.access.listScope(null)).isEqualTo(new PromotionAccess.Scope(List.of(), List.of("gulberg")));
        assertThat(this.access.listScope("b1")).isEqualTo(new PromotionAccess.Scope(List.of(), List.of("gulberg")));
        assertThatThrownBy(() -> this.access.listScope("b2")).isInstanceOf(ModuleException.Forbidden.class);
    }

    @Test
    @DisplayName("operator staff manage every promotion and list them all")
    void operator() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("admin").build(),
                List.of(new SimpleGrantedAuthority(PromotionAccess.MANAGE))));
        assertThatCode(() -> this.access.requireCreate(null, List.of("anything"))).doesNotThrowAnyException();
        assertThatCode(() -> this.access.requireCreate("b1", List.of("dha"))).doesNotThrowAnyException();
        assertThat(this.access.listScope(null)).isNull();
    }

    private static void signIn(List<String> brandIds, List<String> storeIds) {
        Jwt.Builder token = jwt("u1");
        if (!brandIds.isEmpty()) {
            token.claim("brandIds", brandIds);
        }
        if (!storeIds.isEmpty()) {
            token.claim("storeIds", storeIds);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token.build(),
                List.of(new SimpleGrantedAuthority("store:portal"))));
    }

    private static Jwt.Builder jwt(String subject) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(subject);
    }

    private static PromotionDetails details(List<String> storeIds) {
        return new PromotionDetails("p1", "STORE", "b1", "SAVE10", Map.of("en", "10% off"), "PERCENT", 1000, null, 0, 0,
                Instant.EPOCH, null, null, null, false, storeIds, List.of(), List.of(), false, 0, true, 0, 0, null);
    }
}
