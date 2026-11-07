package io.github.ilyaslabs.foodstack.store.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.store.core.application.MenuPrices;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Branch prices and price approval (PRD BB-4, ST-6). A branch's managers and the brand's owners set
 * the branch's own prices; with approval on, their changes wait until operator staff approve them.
 */
@RestController
class MenuPricesController {

    private static final String OPERATOR_PERMISSION = MenuPrices.OPERATOR_PERMISSION;

    private final MenuPrices prices;

    MenuPricesController(MenuPrices prices) {
        this.prices = prices;
    }

    /** {@code priceMinor} {@code null} goes back to the brand price; {@code optionId} names an option's price difference. */
    record BranchPriceRequest(Long priceMinor, @Size(max = 64) String optionId) {
    }

    record BranchPriceResponse(String itemId, String optionId, long priceMinor) {
    }

    record BranchPricesResponse(List<BranchPriceResponse> prices, List<PriceChangeResponse> pending) {
    }

    /** {@code APPLIED}, or {@code PENDING} while it waits for approval. */
    record OutcomeResponse(String outcome) {
    }

    record PriceChangeResponse(String id, String brandId, String brandName, String storeId, String branchName, String itemId,
            Map<String, String> itemName, String optionId, Map<String, String> optionName, long currentMinor,
            Long proposedMinor, String requestedBy, Long requestedAt, String status) {
    }

    @GetMapping("/api/v1/stores/{storeId}/menu/prices")
    @PreAuthorize("isAuthenticated()")
    BranchPricesResponse branchPrices(@PathVariable String storeId) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        MenuPrices.BranchPrices found = this.prices.branchPrices(storeId);
        return new BranchPricesResponse(
                found.prices().stream().map((p) -> new BranchPriceResponse(p.itemId(), p.optionId(), p.priceMinor())).toList(),
                found.pending().stream().map(MenuPricesController::response).toList());
    }

    @PutMapping("/api/v1/stores/{storeId}/menu/items/{itemId}/price")
    @PreAuthorize("isAuthenticated()")
    OutcomeResponse setBranchPrice(@PathVariable String storeId, @PathVariable String itemId,
            @RequestBody BranchPriceRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        return new OutcomeResponse(
                this.prices.setBranchPrice(storeId, itemId, request.optionId(), request.priceMinor(), CurrentUser.id()).name());
    }

    @GetMapping("/api/v1/brands/{brandId}/menu/price-changes")
    @PreAuthorize("isAuthenticated()")
    List<PriceChangeResponse> brandPending(@PathVariable String brandId) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return this.prices.brandPending(brandId).stream().map(MenuPricesController::response).toList();
    }

    @GetMapping("/api/v1/price-changes")
    @PreAuthorize("hasAuthority('store:manage')")
    List<PriceChangeResponse> pending() {
        return this.prices.pending().stream().map(MenuPricesController::response).toList();
    }

    @PostMapping("/api/v1/price-changes/{id}/approve")
    @PreAuthorize("hasAuthority('store:manage')")
    PriceChangeResponse approve(@PathVariable String id) {
        return response(this.prices.decide(id, true, CurrentUser.id()));
    }

    @PostMapping("/api/v1/price-changes/{id}/reject")
    @PreAuthorize("hasAuthority('store:manage')")
    PriceChangeResponse reject(@PathVariable String id) {
        return response(this.prices.decide(id, false, CurrentUser.id()));
    }

    private static PriceChangeResponse response(MenuPrices.PriceChange c) {
        return new PriceChangeResponse(c.id(), c.brandId(), c.brandName(), c.storeId(), c.branchName(), c.itemId(),
                c.itemName(), c.optionId(), c.optionName(), c.currentMinor(), c.proposedMinor(), c.requestedBy(),
                EpochSeconds.of(c.requestedAt()), c.status());
    }
}
