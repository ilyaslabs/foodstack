package io.github.ilyaslabs.foodstack.store.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import java.time.Duration;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.core.application.MenuService;
import io.github.ilyaslabs.foodstack.store.core.application.NewOwner;
import io.github.ilyaslabs.foodstack.store.core.application.NewStore;
import io.github.ilyaslabs.foodstack.store.core.application.StoreAdminUpdate;
import io.github.ilyaslabs.foodstack.store.core.application.StoreDetails;
import io.github.ilyaslabs.foodstack.store.core.application.StoreService;
import io.github.ilyaslabs.foodstack.store.core.application.UpdateBranch;
import io.github.ilyaslabs.foodstack.store.web.dto.MenuDtos;
import io.github.ilyaslabs.foodstack.store.web.dto.StoreDtos;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Branches (PRD ST-1, ST-2, ST-4, ST-10, ST-13, BB-1). Operator staff with {@code store:manage}
 * onboard restaurants and act on any branch; a store owner acts on the branches in their
 * {@code storeIds} claim. Customers read live branches, their menus and delivery quotes without
 * logging in.
 */
@RestController
@RequestMapping("/api/v1/stores")
class StoresController {

    static final Duration SHARED_CACHE = Duration.ofSeconds(30);

    static final String OPERATOR_PERMISSION = "store:manage";

    static final String COLLECTION_PERMISSION = "store:payment-collection:manage";

    private final StoreService storeService;

    private final MenuService menuService;

    StoresController(StoreService storeService, MenuService menuService) {
        this.storeService = storeService;
        this.menuService = menuService;
    }

    // ---- Back office ----------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAuthority('store:manage')")
    @Flow(value = "store-onboard", label = "Onboard restaurant", description = "Creates a brand and its first branch as a draft")
    @EVL.Endpoint(method = "POST", path = "/api/v1/stores", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> onboard(@Valid @RequestBody StoreDtos.OnboardRequest request) {
        StoreDetails created = this.storeService.onboard(new NewStore(request.brandName(), request.branchName(),
                request.legalName(), request.taxId(), request.description(), request.cuisines(),
                StoreMapper.address(request.address()), StoreMapper.point(request.location()), DeliveryMode.SELF,
                request.pickupEnabled(), request.prepMinutes(), StoreMapper.commission(request.commission()),
                request.cityId()), CurrentUser.id());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(created)).body(StoreMapper.store(created));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('store:manage') or hasAuthority('store:portal')")
    @Flow(value = "store-list", label = "List branches", description = "Operator sees all; store users see their own branches and brands")
    @EVL.Endpoint(method = "GET", path = "/api/v1/stores", caller = "Back office")
    StoreDtos.StoreListResponse list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {
        if (!CurrentUser.hasAuthority(OPERATOR_PERMISSION)) {
            // a store-area user: their own branches and every branch of the brands they own (PRD BB-5)
            return new StoreDtos.StoreListResponse(this.storeService.listFor(CurrentUser.storeIds(), CurrentUser.brandIds())
                .stream()
                .map(StoreMapper::summary)
                .toList());
        }
        return new StoreDtos.StoreListResponse(
                this.storeService.list(null, q, limit).stream().map(StoreMapper::summary).toList());
    }

    @GetMapping("/{storeId}/details")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "store-details", label = "Show branch details")
    @EVL.Endpoint(method = "GET", path = "/api/v1/stores/{storeId}/details", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> details(@PathVariable String storeId) {
        requireReadAccess(storeId);
        StoreDetails store = this.storeService.get(storeId);
        return ResponseEntity.ok().eTag(etag(store)).body(StoreMapper.store(store));
    }

    @PutMapping("/{storeId}/branch")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "store-update-branch", label = "Change branch details", description = "Name, address, location, prep time, delivery mode, pickup, time zone, ticket paper")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/branch", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> updateBranch(@PathVariable String storeId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StoreDtos.BranchRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        StoreDetails updated = this.storeService.updateBranch(storeId,
                new UpdateBranch(request.branchName(), StoreMapper.address(request.address()),
                        StoreMapper.point(request.location()), request.prepMinutes(),
                        StoreMapper.deliveryMode(request.deliveryMode()), request.pickupEnabled(), request.timezone(),
                        request.ticketPaperMm()),
                StoreMapper.requireIfMatch("store", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @PutMapping("/{storeId}/admin")
    @PreAuthorize("hasAuthority('store:manage')")
    @Flow(value = "store-update-admin", label = "Change branch admin details", description = "Legal name, tax id, city, operator delivery allowed")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/admin", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> updateAdmin(@PathVariable String storeId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StoreDtos.StoreAdminRequest request) {
        StoreDetails updated = this.storeService.updateAdmin(storeId,
                new StoreAdminUpdate(request.legalName(), request.taxId(), request.operatorDeliveryAllowed(), request.cityId()),
                StoreMapper.requireIfMatch("store", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @PutMapping("/{storeId}/slug")
    @PreAuthorize("hasAuthority('store:manage')")
    @Flow(value = "store-update-slug", label = "Change branch web address")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/slug", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> updateSlug(@PathVariable String storeId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StoreDtos.SlugRequest request) {
        StoreDetails updated = this.storeService.updateSlug(storeId, request.slug(),
                StoreMapper.requireIfMatch("store", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @PutMapping("/{storeId}/hours")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "store-update-hours", label = "Change opening hours", description = "Weekly hours and holiday closures")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/hours", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> updateHours(@PathVariable String storeId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StoreDtos.HoursRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        StoreDetails updated = this.storeService.updateHours(storeId, StoreMapper.hours(request.openingHours()),
                StoreMapper.closures(request.closures()), StoreMapper.requireIfMatch("store", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @PutMapping("/{storeId}/availability")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "store-availability", label = "Close or reopen branch now", description = "Temporary close switch, whatever the timetable")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/availability", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> availability(@PathVariable String storeId,
            @Valid @RequestBody StoreDtos.AvailabilityRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        StoreDetails updated = this.storeService.setTemporarilyClosed(storeId, request.temporarilyClosed(),
                request.reason(), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @PutMapping("/{storeId}/status")
    @PreAuthorize("hasAuthority('store:manage')")
    @Flow(value = "store-status", label = "Change branch status", description = "Draft, live or paused; going live needs a location, menu and delivery area or pickup")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/status", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> status(@PathVariable String storeId,
            @Valid @RequestBody StoreDtos.StatusRequest request) {
        StoreDetails updated = this.storeService.setStatus(storeId, StoreMapper.status(request.status()), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @GetMapping("/{storeId}/readiness")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "store-readiness", label = "Show what blocks going live")
    @EVL.Endpoint(method = "GET", path = "/api/v1/stores/{storeId}/readiness", caller = "Back office")
    StoreDtos.ReadinessResponse readiness(@PathVariable String storeId) {
        requireReadAccess(storeId);
        return new StoreDtos.ReadinessResponse(this.storeService.readiness(storeId));
    }

    /** The branch's own owners and managers. Operator staff and the brand's owners. */
    @GetMapping("/{storeId}/owners")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "store-owners", label = "List branch owners")
    @EVL.Endpoint(method = "GET", path = "/api/v1/stores/{storeId}/owners", caller = "Back office")
    List<StoreDtos.OwnerResponse> owners(@PathVariable String storeId) {
        requireBrandOwnerOf(storeId);
        return this.storeService.owners(storeId).stream().map(StoreMapper::owner).toList();
    }

    /** Adds a branch manager or franchise owner (PRD BB-5, BB-6). Operator staff and the brand's owners. */
    @PostMapping("/{storeId}/owners")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Flow(value = "store-add-owner", label = "Add branch owner", description = "Branch manager or franchise owner, one branch only")
    @EVL.Endpoint(method = "POST", path = "/api/v1/stores/{storeId}/owners", caller = "Back office")
    StoreDtos.OwnerResponse addOwner(@PathVariable String storeId, @Valid @RequestBody StoreDtos.OwnerRequest request) {
        requireBrandOwnerOf(storeId);
        return StoreMapper.owner(this.storeService.addOwner(storeId,
                new NewOwner(request.email(), request.displayName(), request.initialPassword()), CurrentUser.id()));
    }

    @DeleteMapping("/{storeId}/owners/{userId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Flow(value = "store-remove-owner", label = "Remove branch owner")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/stores/{storeId}/owners/{userId}", caller = "Back office")
    void removeOwner(@PathVariable String storeId, @PathVariable String userId) {
        requireBrandOwnerOf(storeId);
        this.storeService.removeOwner(storeId, userId, CurrentUser.id());
    }

    /** The branch's own commission from a date (operator only, PRD BB-8, FI-2). */
    @PutMapping("/{storeId}/commission")
    @PreAuthorize("hasAuthority('store:manage')")
    @Flow(value = "store-branch-commission", label = "Plan branch commission", description = "Branch's own commission from a date; none goes back to the brand's")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/commission", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> commission(@PathVariable String storeId,
            @Valid @RequestBody StoreDtos.CommissionChangeRequest request) {
        StoreDetails updated = this.storeService.updateBranchCommission(storeId, StoreMapper.commission(request.commission()),
                (request.effectiveFrom() != null) ? java.time.Instant.ofEpochSecond(request.effectiveFrom()) : null,
                CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    /** Lets a branch collect card payments itself, or stops it, with its credit limit (finance, PRD DP-2, DP-7). */
    @PutMapping("/{storeId}/payment-collection")
    @PreAuthorize("hasAuthority('store:payment-collection:manage')")
    @Flow(value = "store-payment-collection", label = "Set who collects card payments", description = "Operator or the branch's own merchant account, with a credit limit")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/payment-collection", caller = "Back office")
    ResponseEntity<StoreDtos.StoreResponse> paymentCollection(@PathVariable String storeId,
            @RequestBody StoreDtos.PaymentCollectionRequest request) {
        StoreDetails updated = this.storeService.setPaymentCollection(storeId, request.mode(), request.creditLimitMinor(),
                request.reason(), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    /** Finance reads a branch to set who collects its card payments (PRD DP-2); it edits nothing else. */
    private static void requireReadAccess(String storeId) {
        if (!CurrentUser.hasAuthority(COLLECTION_PERMISSION)) {
            CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        }
    }

    private static void requireBrandOwnerOf(String storeId) {
        if (!CurrentUser.hasAuthority(OPERATOR_PERMISSION) && !CurrentUser.ownsBrandOf(storeId)) {
            throw new io.github.ilyaslabs.moduleswitch.ModuleException.Forbidden("Only the brand's owners manage branch owners",
                    "BRAND_ACCESS_DENIED");
        }
    }

    @PutMapping("/{storeId}/menu/items/{itemId}/sold-out")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Flow(value = "store-sold-out", label = "Mark item sold out", description = "Item or option sold out or available at one branch")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/stores/{storeId}/menu/items/{itemId}/sold-out", caller = "Back office")
    void soldOut(@PathVariable String storeId, @PathVariable String itemId,
            @Valid @RequestBody MenuDtos.SoldOutRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        this.menuService.setSoldOut(storeId, itemId, request.optionId(), request.soldOut(), CurrentUser.id());
    }

    // ---- Customers ------------------------------------------------------------------------

    /** A branch's page by its slug ({@code spice-route-gulberg}) or its id. */
    @GetMapping("/{key}")
    @Flow(value = "store-page", label = "Show store page", description = "By slug or id; live branches only for customers")
    @EVL.Endpoint(method = "GET", path = "/api/v1/stores/{key}", caller = "Customer web")
    ResponseEntity<StoreDtos.StorePageResponse> page(@PathVariable String key) {
        StoreDetails store = visible(this.storeService.resolve(key));
        return ResponseEntity.ok().cacheControl(shared(store)).body(StoreMapper.page(store));
    }

    @GetMapping("/{key}/menu")
    @Flow(value = "store-menu", label = "Show store menu", description = "Brand items for this branch with sold-out marks")
    @EVL.Endpoint(method = "GET", path = "/api/v1/stores/{key}/menu", caller = "Customer web")
    ResponseEntity<MenuDtos.MenuResponse> menu(@PathVariable String key) {
        String storeId = this.storeService.resolve(key);
        StoreDetails store = visible(storeId);
        return ResponseEntity.ok().cacheControl(shared(store)).body(StoreMapper.menu(this.menuService.effectiveMenu(storeId)));
    }

    /**
     * A live branch's page and menu are the same for everyone, so a CDN or the edge proxy may keep
     * them for {@link #SHARED_CACHE} (TDD 7.5) and answer most views without reaching the app; the
     * browser revalidates with the ETag. Checkout prices everything again, so a few seconds of
     * staleness (a sold-out mark) never reaches an order. Drafts are never stored by a shared cache.
     */
    private static CacheControl shared(StoreDetails store) {
        if (store.status() != StoreStatus.LIVE) {
            return CacheControl.noStore();
        }
        return CacheControl.noCache().cachePublic().sMaxAge(SHARED_CACHE);
    }

    @GetMapping("/{key}/delivery-quote")
    @Flow(value = "store-delivery-quote", label = "Quote delivery to an address", description = "Charge, minimum order and time from the branch")
    @EVL.Endpoint(method = "GET", path = "/api/v1/stores/{key}/delivery-quote", caller = "Customer web")
    StoreDtos.QuoteResponse quote(@PathVariable String key, @RequestParam double lat, @RequestParam double lng) {
        String storeId = this.storeService.resolve(key);
        visible(storeId);
        GeoPoint destination = new GeoPoint(lat, lng);
        if (!destination.isValid()) {
            // @flow store-delivery-quote>point: Refuse a point off the map [on error LOCATION_INVALID]
            throw new ModuleException.BadRequest("lat and lng must be a point on the map", "LOCATION_INVALID");
        }
        return StoreMapper.quote(this.storeService.quote(storeId, destination));
    }

    /** A live branch; draft and paused branches are seen only by their owners and operator staff. */
    private StoreDetails visible(String storeId) {
        StoreDetails store = this.storeService.get(storeId);
        if (store.status() != StoreStatus.LIVE && !CurrentUser.hasAuthority(OPERATOR_PERMISSION)
                && !CurrentUser.managesStore(storeId)) {
            throw new ModuleException.NotFound("Store not found", "STORE_NOT_FOUND");
        }
        return store;
    }

    private static String etag(StoreDetails store) {
        return ETags.of("store", store.version());
    }
}
