package io.github.ilyaslabs.foodstack.promotion.core.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.moduleswitch.ModuleException;

/**
 * Who may manage which promotions (PRD PR-3, BB-11). Operator staff ({@code promotion:manage})
 * manage every promotion. A brand owner manages the brand's promotions for all or chosen branches.
 * A branch manager manages promotions for their own branches only, and only while the brand owner
 * allows it; they see the brand's promotions that reach their branch. Branch lists are checked
 * against the shared branch-to-brand map, so a promotion never names another brand's branch.
 */
public class PromotionAccess {

    public static final String MANAGE = "promotion:manage";

    private final BrandRules rules;

    public PromotionAccess(BrandRules rules) {
        this.rules = rules;
    }

    /** Checks that the user may create a promotion of {@code brandId} (null: the operator's) for these branches. */
    public void requireCreate(String brandId, List<String> storeIds) {
        List<String> branches = clean(storeIds);
        if (brandId == null) {
            if (!CurrentUser.hasAuthority(MANAGE)) {
                throw denied("Only the operator creates operator promotions");
            }
            return;
        }
        requireBranchesOf(brandId, branches);
        if (CurrentUser.hasAuthority(MANAGE) || CurrentUser.brandIds().contains(brandId)) {
            return;
        }
        Set<String> own = ownBranches(brandId);
        if (own.isEmpty()) {
            throw new ModuleException.Forbidden("You do not own this brand", "BRAND_ACCESS_DENIED");
        }
        if (!this.rules.branchPromotionsAllowed(brandId)) {
            throw new ModuleException.Forbidden("The brand owner has not allowed branch promotions", "BRANCH_PROMOTIONS_OFF");
        }
        if (branches.isEmpty() || !own.containsAll(branches)) {
            throw new ModuleException.Forbidden("Choose your own branch; the brand owner makes promotions for other branches",
                    "PROMOTION_ACCESS_DENIED");
        }
    }

    /** Checks that the user may change an existing promotion and give it these branches. */
    public void requireEdit(PromotionDetails promotion, List<String> storeIds) {
        requireCreate(promotion.brandId(), promotion.storeIds());
        requireCreate(promotion.brandId(), storeIds);
    }

    /** Checks that the user may see the promotion. */
    public void requireView(PromotionDetails promotion) {
        if (promotion.brandId() == null || CurrentUser.hasAuthority(MANAGE)
                || CurrentUser.brandIds().contains(promotion.brandId())) {
            requireCreate(promotion.brandId(), List.of());
            return;
        }
        Set<String> own = ownBranches(promotion.brandId());
        if (own.isEmpty() || !(promotion.storeIds().isEmpty() || promotion.storeIds().stream().anyMatch(own::contains))) {
            throw new ModuleException.Forbidden("You do not own this brand", "BRAND_ACCESS_DENIED");
        }
    }

    /**
     * What the user's promotion list holds: {@code null} for everything (operator staff), else the
     * brands they own in full and the branches they manage.
     */
    public Scope listScope(String brandId) {
        if (brandId != null) {
            if (CurrentUser.hasAuthority(MANAGE) || CurrentUser.brandIds().contains(brandId)) {
                return new Scope(List.of(brandId), List.of());
            }
            Set<String> own = ownBranches(brandId);
            if (own.isEmpty()) {
                throw new ModuleException.Forbidden("You do not own this brand", "BRAND_ACCESS_DENIED");
            }
            return new Scope(List.of(), List.copyOf(own));
        }
        if (CurrentUser.hasAuthority(MANAGE)) {
            return null;
        }
        List<String> brands = List.copyOf(CurrentUser.brandIds());
        List<String> branches = List.copyOf(CurrentUser.storeIds());
        if (brands.isEmpty() && branches.isEmpty()) {
            throw denied("You cannot manage promotions");
        }
        return new Scope(brands, branches);
    }

    /** Whether the brand lets branch managers create promotions, for the back office. */
    public boolean branchPromotionsAllowed(String brandId) {
        return this.rules.branchPromotionsAllowed(brandId);
    }

    /**
     * A list's reach.
     *
     * @param brandIds brands whose every promotion is listed
     * @param branchIds branches whose brand's promotions are listed when they reach the branch
     */
    public record Scope(List<String> brandIds, List<String> branchIds) {
    }

    private static Set<String> ownBranches(String brandId) {
        Set<String> own = new LinkedHashSet<>();
        for (String storeId : CurrentUser.storeIds()) {
            if (CurrentUser.brandOf(storeId).filter(brandId::equals).isPresent()) {
                own.add(storeId);
            }
        }
        return own;
    }

    private static void requireBranchesOf(String brandId, List<String> branches) {
        for (String storeId : branches) {
            if (CurrentUser.brandOf(storeId).filter(brandId::equals).isEmpty()) {
                throw new ModuleException.BadRequest("The promotion is invalid", "PROMOTION_INVALID",
                        Map.of("errors", Map.of("storeIds", "must be branches of the brand")));
            }
        }
    }

    private static List<String> clean(List<String> values) {
        return (values == null) ? List.of()
                : values.stream().filter((v) -> v != null && !v.isBlank()).map(String::strip).distinct().toList();
    }

    private static ModuleException denied(String message) {
        return new ModuleException.Forbidden(message, "PROMOTION_ACCESS_DENIED");
    }
}
