package io.github.ilyaslabs.foodstack.promotion.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionAccess;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionDetails;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionInput;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionService;
import io.github.ilyaslabs.foodstack.promotion.web.dto.ActiveRequest;
import io.github.ilyaslabs.foodstack.promotion.web.dto.PromotionRequest;
import io.github.ilyaslabs.foodstack.promotion.web.dto.PromotionResponse;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Coupons in the back office (PRD 4.8). Operator staff with {@code promotion:manage} manage the
 * operator's promotions and see every store's; a brand owner manages their own brand's, and a
 * branch manager their own branch's when the brand owner allows it ({@link PromotionAccess}). Edits need the {@code If-Match} of the version the editor saw.
 */
@RestController
@EVL.Endpoint(path = "/api/v1/promotions")
@RequestMapping("/api/v1/promotions")
class PromotionsController {

    static final String MANAGE = PromotionAccess.MANAGE;

    private final PromotionService promotions;

    private final PromotionAccess access;

    PromotionsController(PromotionService promotions, PromotionAccess access) {
        this.promotions = promotions;
        this.access = access;
    }

    @Flow(value = "promotion-list", label = "List promotions", description = "Operator: all; brand and branch users: their own")
    @EVL.Endpoint(method = "GET", path = "", caller = "Back office")
    @GetMapping
    List<PromotionResponse> list(@RequestParam(required = false) String brandId,
            @RequestParam(defaultValue = "200") int limit) {
        // @flow promotion-list>scope: Which brands and branches the user may see
        return this.promotions.listIn(this.access.listScope(brandId), limit).stream().map(PromotionsController::toResponse).toList();
    }

    /** Whether the brand lets branch managers create promotions for their branch (PRD BB-11). */
    @Flow(value = "promotion-brand-rules", label = "May branches create promotions?", description = "The brand owner's rule, kept from store events")
    @EVL.Endpoint(method = "GET", path = "/brands/{brandId}/rules", caller = "Back office")
    @GetMapping("/brands/{brandId}/rules")
    Map<String, Boolean> brandRules(@PathVariable String brandId) {
        this.access.listScope(brandId);
        return Map.of("branchPromotionsAllowed", this.access.branchPromotionsAllowed(brandId));
    }

    @Flow(value = "promotion-view", label = "Get promotion", description = "With ETag")
    @EVL.Endpoint(method = "GET", path = "/{id}", caller = "Back office")
    @GetMapping("/{id}")
    ResponseEntity<PromotionResponse> get(@PathVariable String id) {
        // @flow promotion-view>access: May see this promotion
        PromotionDetails promotion = this.promotions.details(id);
        this.access.requireView(promotion);
        return ResponseEntity.ok().eTag(ETags.of("promotion", promotion.version())).body(toResponse(promotion));
    }

    @Flow(value = "promotion-create", label = "Create coupon", description = "Operator-wide (brandId null) or for a brand or its branches")
    @EVL.Endpoint(method = "POST", path = "", caller = "Back office")
    @PostMapping
    ResponseEntity<PromotionResponse> create(@RequestBody PromotionRequest request) {
        // @flow promotion-create>access: Operator, the brand's owner, or a branch manager when the brand allows it
        this.access.requireCreate(request.brandId(), request.storeIds());
        PromotionDetails created = this.promotions.create(request.brandId(), input(request), CurrentUser.id());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(ETags.of("promotion", created.version())).body(toResponse(created));
    }

    @Flow(value = "promotion-update", label = "Edit coupon", description = "Needs If-Match")
    @EVL.Endpoint(method = "PUT", path = "/{id}", caller = "Back office")
    @PutMapping("/{id}")
    ResponseEntity<PromotionResponse> update(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody PromotionRequest request) {
        // @flow promotion-update>access: May edit this promotion and these branches
        this.access.requireEdit(this.promotions.details(id), request.storeIds());
        Long version = ETags.versionFromIfMatch("promotion", ifMatch);
        if (version == null) {
            throw new ModuleException.BadRequest("Send If-Match with the ETag you edited, so changes are not lost",
                    "IF_MATCH_REQUIRED");
        }
        PromotionDetails updated = this.promotions.update(id, input(request), version, CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("promotion", updated.version())).body(toResponse(updated));
    }

    @Flow(value = "promotion-set-active", label = "Switch coupon on or off", description = "")
    @EVL.Endpoint(method = "POST", path = "/{id}/active", caller = "Back office")
    @PostMapping("/{id}/active")
    PromotionResponse setActive(@PathVariable String id, @RequestBody ActiveRequest request) {
        // @flow promotion-set-active>access: May edit this promotion
        PromotionDetails promotion = this.promotions.details(id);
        this.access.requireEdit(promotion, promotion.storeIds());
        return toResponse(this.promotions.setActive(id, request.active(), CurrentUser.id()));
    }

    private static PromotionInput input(PromotionRequest r) {
        return new PromotionInput(r.code(), r.title(), r.type(), Objects.requireNonNullElse(r.percentBasisPoints(), 0),
                r.maxDiscountMinor(), Objects.requireNonNullElse(r.amountMinor(), 0L),
                Objects.requireNonNullElse(r.minOrderMinor(), 0L), EpochSeconds.toInstant(r.startsAt()),
                EpochSeconds.toInstant(r.endsAt()), r.totalLimit(), r.perCustomerLimit(), Boolean.TRUE.equals(r.firstOrderOnly()),
                r.storeIds(), r.cities(), r.paymentMethods(), Boolean.TRUE.equals(r.operatorMerchantOnly()),
                Objects.requireNonNullElse(r.operatorFundingBasisPoints(), 0));
    }

    private static PromotionResponse toResponse(PromotionDetails p) {
        return new PromotionResponse(p.id(), p.owner(), p.brandId(), p.code(), p.title(), p.type(), p.percentBasisPoints(),
                p.maxDiscountMinor(), p.amountMinor(), p.minOrderMinor(), seconds(p.startsAt()), seconds(p.endsAt()),
                p.totalLimit(), p.perCustomerLimit(), p.firstOrderOnly(), p.storeIds(), p.cities(), p.paymentMethods(),
                p.operatorMerchantOnly(), p.operatorFundingBasisPoints(), p.active(), p.used(), p.version(),
                seconds(p.updatedAt()));
    }

    private static Long seconds(Instant instant) {
        return EpochSeconds.of(instant);
    }
}
