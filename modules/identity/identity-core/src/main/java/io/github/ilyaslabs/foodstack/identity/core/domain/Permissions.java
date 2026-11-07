package io.github.ilyaslabs.foodstack.identity.core.domain;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.ilyaslabs.foodstack.identity.api.Role;

/**
 * Permissions granted by each role; they travel in the {@code perms} claim so modules authorize
 * without a lookup (TDD 9.1). New modules add their permissions here.
 */
public final class Permissions {

    public static final String SETTINGS_MANAGE = "settings:manage";

    public static final String MEDIA_UPLOAD = "media:upload";

    public static final String USERS_READ = "identity:users:read";

    public static final String USERS_MANAGE = "identity:users:manage";

    /** Onboard and manage every store and brand (operator staff). */
    public static final String STORE_MANAGE = "store:manage";

    /** Operator delivery pricing and any store's delivery zones. */
    public static final String GEO_MANAGE = "geo:manage";

    /** Rebuild the search index. */
    public static final String SEARCH_MANAGE = "search:manage";

    /** Create and edit operator coupons and see every store's (PRD PR-3). */
    public static final String PROMOTION_MANAGE = "promotion:manage";

    /** See every order, cancel and refund with a reason (PRD OF-6). */
    public static final String ORDER_MANAGE = "order:manage";

    /** Switch payment methods and see the card gateway's state. */
    public static final String PAYMENT_MANAGE = "payment:manage";

    /**
     * Work in the store area of the back office. Which brands and branches is decided by the
     * {@code brandIds} and {@code storeIds} claims.
     */
    public static final String STORE_PORTAL = "store:portal";

    /** Onboard, edit and suspend operator riders; see every rider (PRD DR-1). */
    public static final String RIDER_MANAGE = "rider:manage";

    /** Live map, reassign riders and hand deliveries back to stores (PRD DR-8, OF-6, OF-8). */
    public static final String DISPATCH_MANAGE = "dispatch:manage";

    /** Work in the rider area of the back office: go online, take jobs, see earnings. */
    public static final String RIDER_PORTAL = "rider:portal";

    /** Read the audit log of back-office writes and sign-ins (PRD AD-3, ID-8). */
    public static final String AUDIT_READ = "identity:audit:read";

    /** See every branch's and rider's balance, payouts and the operator's earnings (PRD FI-3, FI-7). */
    public static final String LEDGER_READ = "ledger:read";

    /** Settle balances with a reference (PRD FI-3). */
    public static final String LEDGER_SETTLE = "ledger:settle";

    /** See the balances and payouts of one's own branches, or brand (PRD FI-7, BB-8). */
    public static final String LEDGER_STORE = "ledger:store";

    private static final Map<Role, Set<String>> BY_ROLE = new EnumMap<>(Role.class);

    static {
        BY_ROLE.put(Role.ADMIN, Set.of(SETTINGS_MANAGE, MEDIA_UPLOAD, USERS_READ, USERS_MANAGE, STORE_MANAGE,
                GEO_MANAGE, SEARCH_MANAGE, PROMOTION_MANAGE, ORDER_MANAGE, PAYMENT_MANAGE, RIDER_MANAGE, DISPATCH_MANAGE, AUDIT_READ,
                LEDGER_READ, LEDGER_SETTLE));
        BY_ROLE.put(Role.FINANCE, Set.of(USERS_READ, PAYMENT_MANAGE, LEDGER_READ, LEDGER_SETTLE));
        BY_ROLE.put(Role.SUPPORT, Set.of(USERS_READ, ORDER_MANAGE, DISPATCH_MANAGE));
        BY_ROLE.put(Role.BRAND_OWNER, Set.of(STORE_PORTAL, MEDIA_UPLOAD, LEDGER_STORE));
        BY_ROLE.put(Role.STORE_OWNER, Set.of(STORE_PORTAL, MEDIA_UPLOAD, LEDGER_STORE));
        BY_ROLE.put(Role.STORE_STAFF, Set.of(STORE_PORTAL));
        BY_ROLE.put(Role.RIDER, Set.of(RIDER_PORTAL, MEDIA_UPLOAD));
        BY_ROLE.put(Role.STORE_RIDER, Set.of(RIDER_PORTAL, MEDIA_UPLOAD));
        BY_ROLE.put(Role.CUSTOMER, Set.of());
    }

    private Permissions() {
    }

    /** Every permission of a set of roles, sorted. */
    public static Set<String> of(Collection<Role> roles) {
        Set<String> permissions = new TreeSet<>();
        for (Role role : roles) {
            permissions.addAll(BY_ROLE.getOrDefault(role, Set.of()));
        }
        return permissions;
    }

    /** Back-office roles are everything but {@link Role#CUSTOMER}. */
    public static boolean isBackOffice(Collection<Role> roles) {
        return roles.stream().anyMatch((role) -> role != Role.CUSTOMER);
    }
}
