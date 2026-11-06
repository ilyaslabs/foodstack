package io.github.ilyaslabs.foodstack.store.web.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Public REST shapes of brands and branches. Times are UTC epoch seconds; amounts are minor units. */
public final class StoreDtos {

    private StoreDtos() {
    }

    /** A point on the map. */
    public record LatLng(@DecimalMin("-90") @DecimalMax("90") double lat, @DecimalMin("-180") @DecimalMax("180") double lng) {
    }

    /** Postal address. */
    public record AddressDto(@NotBlank @Size(max = 160) String line1, @Size(max = 80) String area,
            @NotBlank @Size(max = 80) String city, @Size(max = 20) String postalCode) {
    }

    /** Commission: basis points (1500 = 15%) and/or a fixed amount per order. */
    public record CommissionDto(@Min(0) @Max(10_000) int percentBasisPoints, @Min(0) long fixedMinor) {
    }

    /** Opening range {@code HH:mm}. */
    public record TimeRangeDto(@NotBlank String open, @NotBlank String close) {
    }

    /** Holiday closure, ISO dates, both included. */
    public record ClosureDto(@NotBlank String from, @NotBlank String to, @Size(max = 120) String reason) {
    }

    /** Logo or cover with its variant URLs. */
    public record ImageDto(String mediaId, Map<String, String> urls) {
    }

    /** {@code POST /api/v1/stores}: onboard a restaurant (brand plus first branch). */
    public record OnboardRequest(@NotBlank @Size(max = 80) String brandName, @Size(max = 60) String branchName,
            @NotBlank @Size(max = 120) String legalName, @Size(max = 40) String taxId, Map<String, String> description,
            List<String> cuisines, @NotNull @Valid AddressDto address, @Valid LatLng location, boolean pickupEnabled,
            @Min(5) @Max(120) int prepMinutes, @NotNull @Valid CommissionDto commission, @Size(max = 64) String cityId) {
    }

    /** {@code POST /api/v1/brands/{id}/branches}: another branch of the brand (PRD BB-1). */
    public record NewBranchRequest(@NotBlank @Size(max = 60) String branchName, @NotBlank @Size(max = 120) String legalName,
            @Size(max = 40) String taxId, @NotNull @Valid AddressDto address, @Valid LatLng location,
            @Size(max = 64) String cityId, @Min(5) @Max(120) int prepMinutes, boolean pickupEnabled) {
    }

    /**
     * {@code PUT /api/v1/brands/{id}/commission} or {@code PUT /api/v1/stores/{id}/commission} (operator only, PRD
     * FI-2): the commission from {@code effectiveFrom} (UTC epoch seconds, {@code null} for now). On a branch a
     * {@code null} commission goes back to the brand's.
     */
    public record CommissionChangeRequest(@Valid CommissionDto commission, Long effectiveFrom) {
    }

    /** A commission and when it starts; {@code effectiveFrom} 0 is the starting value. */
    public record CommissionChangeResponse(CommissionDto commission, long effectiveFrom, String setBy, Long setAt) {
    }

    /** {@code PUT /api/v1/brands/{id}/branch-promotions}: whether branch managers may create promotions (PRD BB-11). */
    public record BranchPromotionsRequest(boolean allowed) {
    }

    /** {@code PUT /api/v1/brands/{id}} with {@code If-Match}. */
    public record BrandRequest(@NotBlank @Size(max = 80) String name, Map<String, String> description, List<String> cuisines,
            String logoMediaId, String coverMediaId) {
    }

    /**
     * {@code PUT /api/v1/stores/{id}/branch} with {@code If-Match}. Without a {@code timezone}, or with the
     * current one while the location moves, the zone is found from the location.
     */
    public record BranchRequest(@Size(max = 60) String branchName, @NotNull @Valid AddressDto address, @Valid LatLng location,
            @Min(5) @Max(120) int prepMinutes, @NotBlank String deliveryMode, boolean pickupEnabled,
            @Size(max = 64) String timezone, Integer ticketPaperMm) {
    }

    /** {@code PUT /api/v1/stores/{id}/admin} with {@code If-Match} (operator only). */
    public record StoreAdminRequest(@NotBlank @Size(max = 120) String legalName, @Size(max = 40) String taxId,
            boolean operatorDeliveryAllowed, @Size(max = 64) String cityId) {
    }

    /** {@code PUT /api/v1/stores/{id}/slug} with {@code If-Match}: the branch's web address (operator only). */
    public record SlugRequest(@NotBlank @Size(max = 60) String slug) {
    }

    /** {@code PUT /api/v1/stores/{id}/hours} with {@code If-Match}: ranges keyed MONDAY to SUNDAY. */
    public record HoursRequest(@NotNull Map<String, List<@Valid TimeRangeDto>> openingHours, List<@Valid ClosureDto> closures) {
    }

    /** {@code PUT /api/v1/stores/{id}/availability}: the temporarily-closed switch. */
    public record AvailabilityRequest(boolean temporarilyClosed, @Size(max = 120) String reason) {
    }

    /** {@code PUT /api/v1/stores/{id}/status} (operator only). */
    public record StatusRequest(@NotBlank String status) {
    }

    /** {@code POST /api/v1/stores/{id}/owners}. */
    public record OwnerRequest(@NotBlank @Size(max = 254) String email, @Size(max = 80) String displayName,
            @Size(max = 128) String initialPassword) {

        @Override
        public String toString() {
            return "OwnerRequest[displayName=" + this.displayName + "]";
        }
    }

    /** Someone with owner access. */
    public record OwnerResponse(String userId, String email, String displayName) {
    }

    /** A brand. */
    public record BrandResponse(String id, String name, Map<String, String> description, List<String> cuisines,
            ImageDto logo, ImageDto cover, CommissionDto commission, int branchCount, long version, Long updatedAt,
            List<CommissionChangeResponse> commissionChanges, Set<String> ownerUserIds, boolean branchPromotionsAllowed) {
    }

    /** A branch with its brand, for the back office. */
    public record StoreResponse(String id, String slug, BrandResponse brand, String branchName, String displayName, String legalName,
            String taxId, Set<String> ownerUserIds, AddressDto address, LatLng location,
            Map<String, List<TimeRangeDto>> openingHours, List<ClosureDto> closures, boolean temporarilyClosed,
            String closedReason, int prepMinutes, String deliveryMode, boolean operatorDeliveryAllowed, boolean pickupEnabled,
            CommissionDto commission, String status, String timezone, boolean openNow, long version, Long createdAt,
            Long updatedAt, String cityId, int ticketPaperMm, List<CommissionChangeResponse> commissionChanges,
            Set<String> brandOwnerUserIds) {
    }

    /** A branch in a back-office list. */
    public record StoreSummaryResponse(String id, String brandId, String brandName, String branchName, String city,
            String status, boolean temporarilyClosed, String deliveryMode, Long createdAt, Map<String, String> logoUrls,
            String cityId, int branchCount) {
    }

    /** Back-office list of branches. */
    public record StoreListResponse(List<StoreSummaryResponse> items) {
    }

    /** What still blocks going live: LOCATION, MENU, DELIVERY_AREA. */
    public record ReadinessResponse(List<String> missing) {
    }

    /** A branch as customers see it. */
    public record StorePageResponse(String id, String slug, String brandId, String brandName, String branchName, String displayName,
            Map<String, String> description, List<String> cuisines, Map<String, String> logoUrls,
            Map<String, String> coverUrls, String address, LatLng location, boolean openNow, boolean temporarilyClosed,
            String closedReason, Map<String, List<TimeRangeDto>> openingHours, int prepMinutes, String deliveryMode,
            boolean pickupEnabled, String timezone) {
    }

    /** Delivery charge and time from a branch to an address. */
    public record QuoteResponse(boolean deliverable, String reason, long chargeMinor, long minOrderMinor,
            Long freeDeliveryAboveMinor, long distanceMeters, int etaMinutes, String currency) {
    }
}
