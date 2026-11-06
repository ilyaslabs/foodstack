package io.github.ilyaslabs.foodstack.store.web;

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

import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
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

    private final StoreService storeService;

    private final MenuService menuService;

    StoresController(StoreService storeService, MenuService menuService) {
        this.storeService = storeService;
        this.menuService = menuService;
    }

    // ---- Back office ----------------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAuthority('store:manage')")
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
    ResponseEntity<StoreDtos.StoreResponse> details(@PathVariable String storeId) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        StoreDetails store = this.storeService.get(storeId);
        return ResponseEntity.ok().eTag(etag(store)).body(StoreMapper.store(store));
    }

    @PutMapping("/{storeId}/branch")
    @PreAuthorize("isAuthenticated()")
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
    ResponseEntity<StoreDtos.StoreResponse> updateSlug(@PathVariable String storeId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StoreDtos.SlugRequest request) {
        StoreDetails updated = this.storeService.updateSlug(storeId, request.slug(),
                StoreMapper.requireIfMatch("store", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @PutMapping("/{storeId}/hours")
    @PreAuthorize("isAuthenticated()")
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
    ResponseEntity<StoreDtos.StoreResponse> availability(@PathVariable String storeId,
            @Valid @RequestBody StoreDtos.AvailabilityRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        StoreDetails updated = this.storeService.setTemporarilyClosed(storeId, request.temporarilyClosed(),
                request.reason(), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @PutMapping("/{storeId}/status")
    @PreAuthorize("hasAuthority('store:manage')")
    ResponseEntity<StoreDtos.StoreResponse> status(@PathVariable String storeId,
            @Valid @RequestBody StoreDtos.StatusRequest request) {
        StoreDetails updated = this.storeService.setStatus(storeId, StoreMapper.status(request.status()), CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
    }

    @GetMapping("/{storeId}/readiness")
    @PreAuthorize("isAuthenticated()")
    StoreDtos.ReadinessResponse readiness(@PathVariable String storeId) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        return new StoreDtos.ReadinessResponse(this.storeService.readiness(storeId));
    }

    /** The branch's own owners and managers. Operator staff and the brand's owners. */
    @GetMapping("/{storeId}/owners")
    @PreAuthorize("isAuthenticated()")
    List<StoreDtos.OwnerResponse> owners(@PathVariable String storeId) {
        requireBrandOwnerOf(storeId);
        return this.storeService.owners(storeId).stream().map(StoreMapper::owner).toList();
    }

    /** Adds a branch manager or franchise owner (PRD BB-5, BB-6). Operator staff and the brand's owners. */
    @PostMapping("/{storeId}/owners")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    StoreDtos.OwnerResponse addOwner(@PathVariable String storeId, @Valid @RequestBody StoreDtos.OwnerRequest request) {
        requireBrandOwnerOf(storeId);
        return StoreMapper.owner(this.storeService.addOwner(storeId,
                new NewOwner(request.email(), request.displayName(), request.initialPassword()), CurrentUser.id()));
    }

    @DeleteMapping("/{storeId}/owners/{userId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeOwner(@PathVariable String storeId, @PathVariable String userId) {
        requireBrandOwnerOf(storeId);
        this.storeService.removeOwner(storeId, userId, CurrentUser.id());
    }

    /** The branch's own commission from a date (operator only, PRD BB-8, FI-2). */
    @PutMapping("/{storeId}/commission")
    @PreAuthorize("hasAuthority('store:manage')")
    ResponseEntity<StoreDtos.StoreResponse> commission(@PathVariable String storeId,
            @Valid @RequestBody StoreDtos.CommissionChangeRequest request) {
        StoreDetails updated = this.storeService.updateBranchCommission(storeId, StoreMapper.commission(request.commission()),
                (request.effectiveFrom() != null) ? java.time.Instant.ofEpochSecond(request.effectiveFrom()) : null,
                CurrentUser.id());
        return ResponseEntity.ok().eTag(etag(updated)).body(StoreMapper.store(updated));
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
    void soldOut(@PathVariable String storeId, @PathVariable String itemId,
            @Valid @RequestBody MenuDtos.SoldOutRequest request) {
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        this.menuService.setSoldOut(storeId, itemId, request.optionId(), request.soldOut(), CurrentUser.id());
    }

    // ---- Customers ------------------------------------------------------------------------

    /** A branch's page by its slug ({@code spice-route-gulberg}) or its id. */
    @GetMapping("/{key}")
    ResponseEntity<StoreDtos.StorePageResponse> page(@PathVariable String key) {
        StoreDetails store = visible(this.storeService.resolve(key));
        return ResponseEntity.ok().cacheControl(shared(store)).body(StoreMapper.page(store));
    }

    @GetMapping("/{key}/menu")
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
    StoreDtos.QuoteResponse quote(@PathVariable String key, @RequestParam double lat, @RequestParam double lng) {
        String storeId = this.storeService.resolve(key);
        visible(storeId);
        GeoPoint destination = new GeoPoint(lat, lng);
        if (!destination.isValid()) {
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
