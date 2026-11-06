package io.github.ilyaslabs.foodstack.store.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.foodstack.store.core.application.BrandDetails;
import io.github.ilyaslabs.foodstack.store.core.application.MenuService;
import io.github.ilyaslabs.foodstack.store.core.application.NewBranch;
import io.github.ilyaslabs.foodstack.store.core.application.NewOwner;
import io.github.ilyaslabs.foodstack.store.core.application.StoreDetails;
import io.github.ilyaslabs.foodstack.store.core.application.StoreService;
import io.github.ilyaslabs.foodstack.store.core.application.UpdateBrand;
import io.github.ilyaslabs.foodstack.store.web.dto.MenuDtos;
import io.github.ilyaslabs.foodstack.store.web.dto.StoreDtos;
import jakarta.validation.Valid;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brands and their menus (PRD ST-3, ST-5, BB-2, BB-3). One menu per brand; each branch shows it
 * filtered to the branch. The brand's owner edits it ({@code brandIds} claim); operator staff with
 * {@code store:manage} can edit any brand and set its commission.
 */
@RestController
@RequestMapping("/api/v1/brands")
class BrandsController {

    private static final String OPERATOR_PERMISSION = StoresController.OPERATOR_PERMISSION;

    private final StoreService storeService;

    private final MenuService menuService;

    BrandsController(StoreService storeService, MenuService menuService) {
        this.storeService = storeService;
        this.menuService = menuService;
    }

    @GetMapping("/{brandId}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<StoreDtos.BrandResponse> brand(@PathVariable String brandId) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        BrandDetails brand = this.storeService.getBrand(brandId);
        return ResponseEntity.ok().eTag(ETags.of("brand", brand.version())).body(StoreMapper.brand(brand));
    }

    @PutMapping("/{brandId}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<StoreDtos.BrandResponse> update(@PathVariable String brandId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StoreDtos.BrandRequest request) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        BrandDetails updated = this.storeService.updateBrand(brandId,
                new UpdateBrand(request.name(), request.description(), request.cuisines(), request.logoMediaId(),
                        request.coverMediaId()),
                StoreMapper.requireIfMatch("brand", ifMatch), CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("brand", updated.version())).body(StoreMapper.brand(updated));
    }

    /** The brand's commission, now or from a date (operator only, PRD FI-2, BB-8). */
    @PutMapping("/{brandId}/commission")
    @PreAuthorize("hasAuthority('store:manage')")
    ResponseEntity<StoreDtos.BrandResponse> commission(@PathVariable String brandId,
            @Valid @RequestBody StoreDtos.CommissionChangeRequest request) {
        if (request.commission() == null) {
            throw new io.github.ilyaslabs.moduleswitch.ModuleException.BadRequest("A brand always has a commission",
                    "COMMISSION_INVALID");
        }
        BrandDetails updated = this.storeService.updateCommission(brandId, StoreMapper.commission(request.commission()),
                (request.effectiveFrom() != null) ? java.time.Instant.ofEpochSecond(request.effectiveFrom()) : null,
                CurrentUser.id());
        return ResponseEntity.ok().eTag(ETags.of("brand", updated.version())).body(StoreMapper.brand(updated));
    }

    // ---- Branches and owners (PRD BB-1, BB-5) ----------------------------------------------

    @GetMapping("/{brandId}/branches")
    @PreAuthorize("isAuthenticated()")
    StoreDtos.StoreListResponse branches(@PathVariable String brandId) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return new StoreDtos.StoreListResponse(this.storeService.branches(brandId).stream().map(StoreMapper::summary).toList());
    }

    /** Adds a branch in draft; the operator makes it live. Operator staff and the brand's owners. */
    @PostMapping("/{brandId}/branches")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<StoreDtos.StoreResponse> addBranch(@PathVariable String brandId,
            @Valid @RequestBody StoreDtos.NewBranchRequest request) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        StoreDetails created = this.storeService.addBranch(brandId, new NewBranch(request.branchName(), request.legalName(),
                request.taxId(), StoreMapper.address(request.address()), StoreMapper.point(request.location()),
                request.cityId(), request.prepMinutes(), request.pickupEnabled()), CurrentUser.id());
        return ResponseEntity.status(HttpStatus.CREATED)
            .eTag(ETags.of("store", created.version()))
            .body(StoreMapper.store(created));
    }

    @GetMapping("/{brandId}/owners")
    @PreAuthorize("isAuthenticated()")
    List<StoreDtos.OwnerResponse> owners(@PathVariable String brandId) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return this.storeService.brandOwners(brandId).stream().map(StoreMapper::owner).toList();
    }

    /** Brand owners are given by the operator. */
    @PostMapping("/{brandId}/owners")
    @PreAuthorize("hasAuthority('store:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    StoreDtos.OwnerResponse addOwner(@PathVariable String brandId, @Valid @RequestBody StoreDtos.OwnerRequest request) {
        return StoreMapper.owner(this.storeService.addBrandOwner(brandId,
                new NewOwner(request.email(), request.displayName(), request.initialPassword()), CurrentUser.id()));
    }

    @DeleteMapping("/{brandId}/owners/{userId}")
    @PreAuthorize("hasAuthority('store:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeOwner(@PathVariable String brandId, @PathVariable String userId) {
        this.storeService.removeBrandOwner(brandId, userId, CurrentUser.id());
    }

    @PutMapping("/{brandId}/branch-promotions")
    @PreAuthorize("isAuthenticated()")
    StoreDtos.BrandResponse branchPromotions(@PathVariable String brandId,
            @Valid @RequestBody StoreDtos.BranchPromotionsRequest request) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return StoreMapper.brand(this.storeService.setBranchPromotionsAllowed(brandId, request.allowed(), CurrentUser.id()));
    }

    // ---- Menu editor ----------------------------------------------------------------------

    /**
     * The brand menu for editing. With {@code storeId}, only the items that branch sells, and a
     * branch manager may read it too (to mark items sold out and set the branch's own prices).
     */
    @GetMapping("/{brandId}/menu")
    @PreAuthorize("isAuthenticated()")
    MenuDtos.EditorMenuResponse menu(@PathVariable String brandId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String storeId) {
        if (storeId == null) {
            CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
            return StoreMapper.editorMenu(this.menuService.editorMenu(brandId));
        }
        CurrentUser.requireStoreAccess(storeId, OPERATOR_PERMISSION);
        return StoreMapper.editorMenu(this.menuService.branchEditorMenu(brandId, storeId));
    }

    @PostMapping("/{brandId}/menu/sections")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    MenuDtos.SectionResponse createSection(@PathVariable String brandId, @Valid @RequestBody MenuDtos.SectionRequest request) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return StoreMapper.section(this.menuService.createSection(brandId, request.name(), CurrentUser.id()));
    }

    @PutMapping("/{brandId}/menu/sections/{sectionId}")
    @PreAuthorize("isAuthenticated()")
    MenuDtos.SectionResponse updateSection(@PathVariable String brandId, @PathVariable String sectionId,
            @Valid @RequestBody MenuDtos.SectionRequest request) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return StoreMapper.section(this.menuService.updateSection(brandId, sectionId, request.name(),
                (request.sortOrder() != null) ? request.sortOrder() : 0, StoreMapper.requireVersion(request.version()),
                CurrentUser.id()));
    }

    @DeleteMapping("/{brandId}/menu/sections/{sectionId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSection(@PathVariable String brandId, @PathVariable String sectionId) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        this.menuService.deleteSection(brandId, sectionId, CurrentUser.id());
    }

    @PostMapping("/{brandId}/menu/items")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    MenuDtos.ItemResponse createItem(@PathVariable String brandId, @Valid @RequestBody MenuDtos.ItemRequest request) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return StoreMapper.item(this.menuService.createItem(brandId, StoreMapper.item(request), CurrentUser.id()));
    }

    @PutMapping("/{brandId}/menu/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    MenuDtos.ItemResponse updateItem(@PathVariable String brandId, @PathVariable String itemId,
            @Valid @RequestBody MenuDtos.ItemRequest request) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        return StoreMapper.item(this.menuService.updateItem(brandId, itemId, StoreMapper.item(request),
                StoreMapper.requireVersion(request.version()),
                CurrentUser.id()));
    }

    @DeleteMapping("/{brandId}/menu/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteItem(@PathVariable String brandId, @PathVariable String itemId) {
        CurrentUser.requireBrandAccess(brandId, OPERATOR_PERMISSION);
        this.menuService.deleteItem(brandId, itemId, CurrentUser.id());
    }
}
