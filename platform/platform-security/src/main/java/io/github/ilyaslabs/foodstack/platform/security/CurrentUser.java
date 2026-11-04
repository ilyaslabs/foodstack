package io.github.ilyaslabs.foodstack.platform.security;

import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The user behind the current request, from the validated token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The {@code sub} of the current token; 401 when there is none. */
    public static String id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getSubject();
        }
        throw new ModuleException.Unauthorized("Authentication required", "UNAUTHENTICATED");
    }

    /** The {@code sid} of the current token: the sign-in it belongs to, or {@code null}. */
    public static String sessionId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getClaimAsString(TokenClaims.SESSION_ID);
        }
        return null;
    }

    /** Whether the current token grants {@code authority} (a permission such as {@code store:manage}). */
    public static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream().anyMatch((granted) -> authority.equals(granted.getAuthority()));
    }

    /** The {@code brandIds} claim: brands the user owns; empty for everyone else. */
    public static java.util.Set<String> brandIds() {
        return listClaim(TokenClaims.BRAND_IDS);
    }

    /** The {@code storeIds} claim: branches the user manages; empty for everyone else. */
    public static java.util.Set<String> storeIds() {
        return listClaim(TokenClaims.STORE_IDS);
    }

    private static java.util.Set<String> listClaim(String claim) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            java.util.List<String> values = jwt.getToken().getClaimAsStringList(claim);
            return (values != null) ? java.util.Set.copyOf(values) : java.util.Set.of();
        }
        return java.util.Set.of();
    }

    /**
     * Fails with 403 {@code STORE_ACCESS_DENIED} unless the user holds {@code operatorPermission}
     * (operator staff) or manages the branch through the {@code storeIds} claim (TDD 9.1).
     */
    public static void requireStoreAccess(String storeId, String operatorPermission) {
        if (!hasAuthority(operatorPermission) && !storeIds().contains(storeId)) {
            throw new ModuleException.Forbidden("You do not manage this branch", "STORE_ACCESS_DENIED");
        }
    }

    /** As {@link #requireStoreAccess}, for brand-level actions such as editing the menu ({@code brandIds} claim). */
    public static void requireBrandAccess(String brandId, String operatorPermission) {
        if (!hasAuthority(operatorPermission) && !brandIds().contains(brandId)) {
            throw new ModuleException.Forbidden("You do not own this brand", "BRAND_ACCESS_DENIED");
        }
    }
}
