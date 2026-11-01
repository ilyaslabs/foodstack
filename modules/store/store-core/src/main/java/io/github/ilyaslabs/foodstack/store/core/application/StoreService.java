package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.StoreAccessGrant;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.api.events.BrandChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreAvailabilityChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreChanged;
import io.github.ilyaslabs.foodstack.store.core.domain.OpeningHours;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brands and their branches (PRD 4.2, 4.2.1): onboarding, brand and branch details, opening hours,
 * the temporarily-closed switch, visibility and owner access. Every change is versioned, checked
 * against the version the editor saw where people edit forms, and published on the outbox.
 */
public class StoreService {

    static final Pattern CUISINE = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} &'-]{0,29}");

    private static final int MAX_RANGES_PER_DAY = 4;

    private static final double RIDER_KMH = 20;

    private final BrandRepository brands;

    private final StoreRepository stores;

    private final MenuItemRepository items;

    private final SettingsApi settings;

    private final MediaApi media;

    private final IdentityApi identity;

    private final GeoApi geo;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public StoreService(BrandRepository brands, StoreRepository stores, MenuItemRepository items, SettingsApi settings,
            MediaApi media, IdentityApi identity, GeoApi geo, OutboxWriter outbox, TransactionTemplate transaction,
            Clock clock) {
        this.brands = brands;
        this.stores = stores;
        this.items = items;
        this.settings = settings;
        this.media = media;
        this.identity = identity;
        this.geo = geo;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    // ---- Onboarding and reads -------------------------------------------------------------

    /**
     * Onboards a restaurant (PRD ST-1, BB-1): a brand with the same name and its first branch, in
     * {@code DRAFT} until the operator makes it live. Opening hours start at 11:00 to 23:00 daily.
     */
    public StoreDetails onboard(NewStore command, String userId) {
        LocaleView locale = this.settings.getLocale();
        Map<String, String> errors = new LinkedHashMap<>();
        requireText(errors, "brandName", command.brandName(), 1, 80);
        optionalText(errors, "branchName", command.branchName(), 60);
        requireText(errors, "legalName", command.legalName(), 1, 120);
        optionalText(errors, "taxId", command.taxId(), 40);
        Translations.validate("description", command.description(), locale.languages(), locale.defaultLanguage(), false, 500,
                errors);
        List<String> cuisines = cuisines(command.cuisines(), errors);
        validate(errors, command.address(), command.location());
        if (command.prepMinutes() < 5 || command.prepMinutes() > 120) {
            errors.put("prepMinutes", "must be 5 to 120 minutes");
        }
        validate(errors, command.commission());
        if (command.deliveryMode() == DeliveryMode.OPERATOR) {
            errors.put("deliveryMode", "operator delivery is allowed per branch after onboarding");
        }
        failIfAny(errors, "STORE_INVALID");

        Instant now = this.clock.instant();
        BrandDocument brand = new BrandDocument();
        brand.setId(Ids.newId());
        brand.setName(command.brandName().trim());
        brand.setDescription(Translations.clean(command.description(), locale.languages()));
        brand.setCuisines(cuisines);
        brand.setCommission(command.commission());
        brand.setBranchCount(1);
        brand.setCreatedAt(now);
        brand.setCreatedBy(userId);
        brand.setUpdatedAt(now);
        brand.setUpdatedBy(userId);

        StoreDocument store = new StoreDocument();
        store.setId(Ids.newId());
        store.setBrandId(brand.getId());
        store.setBrandName(brand.getName());
        store.setBranchName(blankToNull(command.branchName()));
        store.setLegalName(command.legalName().trim());
        store.setTaxId(blankToNull(command.taxId()));
        store.setAddress(clean(command.address()));
        store.setLocation(point(command.location()));
        store.setOpeningHours(defaultHours());
        store.setPrepMinutes(command.prepMinutes());
        store.setDeliveryMode(DeliveryMode.SELF);
        store.setPickupEnabled(command.pickupEnabled());
        store.setStatus(StoreStatus.DRAFT);
        store.setCreatedAt(now);
        store.setCreatedBy(userId);
        store.setUpdatedAt(now);
        store.setUpdatedBy(userId);

        StoreDocument saved = this.transaction.execute((status) -> {
            BrandDocument savedBrand = this.brands.insert(brand);
            StoreDocument result = this.stores.insert(store);
            this.outbox.append(new BrandChanged(savedBrand.getId(), version(savedBrand.getVersion()), userId));
            this.outbox.append(new StoreChanged(result.getId(), result.getBrandId(), version(result.getVersion()), userId));
            return result;
        });
        return details(saved, brand, locale);
    }

    public StoreDetails get(String storeId) {
        StoreDocument store = findStore(storeId);
        return details(store, findBrand(store.getBrandId()), this.settings.getLocale());
    }

    public BrandDetails getBrand(String brandId) {
        return brandDetails(findBrand(brandId));
    }

    /**
     * Branches for a back-office list, newest first.
     * @param onlyIds limit to these branches (a store owner's), or {@code null} for every branch
     * @param query optional text matched against brand and branch names
     */
    public List<StoreSummary> list(Set<String> onlyIds, String query, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        List<StoreDocument> found = (onlyIds != null) ? this.stores.findByIdInOrderByIdDesc(onlyIds)
                : this.stores.findAllByOrderByIdDesc(Limit.of((query == null || query.isBlank()) ? size : 1000));
        String needle = (query != null) ? query.trim().toLowerCase(Locale.ROOT) : "";
        List<StoreDocument> page = found.stream()
            .filter((s) -> needle.isEmpty() || contains(s.getBrandName(), needle) || contains(s.getBranchName(), needle))
            .limit(size)
            .toList();
        // Logos live on the brand: one read for the whole page.
        Map<String, Map<String, String>> logos = new HashMap<>();
        this.brands.findAllById(page.stream().map(StoreDocument::getBrandId).collect(Collectors.toSet()))
            .forEach((b) -> logos.put(b.getId(), Map.copyOf(b.getLogoUrls())));
        return page.stream()
            .map((s) -> new StoreSummary(s.getId(), s.getBrandId(), s.getBrandName(), s.getBranchName(),
                    (s.getAddress() != null) ? s.getAddress().city() : null, s.getStatus(), s.isTemporarilyClosed(),
                    s.getDeliveryMode(), s.getCreatedAt(), logos.getOrDefault(s.getBrandId(), Map.of())))
            .toList();
    }

    // ---- Brand ---------------------------------------------------------------------------

    /** Replaces the brand's name, description, cuisines and images (PRD BB-2). */
    public BrandDetails updateBrand(String brandId, UpdateBrand update, long expectedVersion, String userId) {
        LocaleView locale = this.settings.getLocale();
        Map<String, String> errors = new LinkedHashMap<>();
        requireText(errors, "name", update.name(), 1, 80);
        Translations.validate("description", update.description(), locale.languages(), locale.defaultLanguage(), false, 500,
                errors);
        List<String> cuisines = cuisines(update.cuisines(), errors);
        failIfAny(errors, "BRAND_INVALID");
        BrandDocument brand = findBrand(brandId);
        requireVersion("brand", brand.getVersion(), expectedVersion, "BRAND_VERSION_CONFLICT");
        MediaOwner owner = new MediaOwner("store", "brand-" + brandId);
        String oldLogo = brand.getLogoMediaId();
        String oldCover = brand.getCoverMediaId();
        String newLogo = blankToNull(update.logoMediaId());
        String newCover = blankToNull(update.coverMediaId());
        if (newLogo != null && !newLogo.equals(oldLogo)) {
            brand.setLogoUrls(urls(this.media.claim(newLogo, MediaPurpose.STORE, owner)));
        }
        else if (newLogo == null) {
            brand.setLogoUrls(new LinkedHashMap<>());
        }
        if (newCover != null && !newCover.equals(oldCover)) {
            brand.setCoverUrls(urls(this.media.claim(newCover, MediaPurpose.STORE, owner)));
        }
        else if (newCover == null) {
            brand.setCoverUrls(new LinkedHashMap<>());
        }
        brand.setLogoMediaId(newLogo);
        brand.setCoverMediaId(newCover);
        brand.setName(update.name().trim());
        brand.setDescription(Translations.clean(update.description(), locale.languages()));
        brand.setCuisines(cuisines);
        brand.setUpdatedAt(this.clock.instant());
        brand.setUpdatedBy(userId);
        List<StoreDocument> branches = this.stores.findByBrandId(brandId);
        BrandDocument saved = this.transaction.execute((status) -> {
            BrandDocument result = this.brands.save(brand);
            for (StoreDocument branch : branches) {
                if (!Objects.equals(branch.getBrandName(), result.getName())) {
                    branch.setBrandName(result.getName());
                    this.stores.save(branch);
                }
            }
            this.outbox.append(new BrandChanged(brandId, version(result.getVersion()), userId));
            return result;
        });
        releaseIfReplaced(oldLogo, newLogo, owner);
        releaseIfReplaced(oldCover, newCover, owner);
        return brandDetails(saved);
    }

    /** Sets the brand's default commission (operator only, PRD ST-1, BB-8). */
    public BrandDetails updateCommission(String brandId, Commission commission, String userId) {
        Map<String, String> errors = new LinkedHashMap<>();
        validate(errors, commission);
        failIfAny(errors, "COMMISSION_INVALID");
        BrandDocument brand = findBrand(brandId);
        brand.setCommission(commission);
        brand.setUpdatedAt(this.clock.instant());
        brand.setUpdatedBy(userId);
        BrandDocument saved = this.transaction.execute((status) -> {
            BrandDocument result = this.brands.save(brand);
            this.outbox.append(new BrandChanged(brandId, version(result.getVersion()), userId));
            return result;
        });
        return brandDetails(saved);
    }

    // ---- Branch --------------------------------------------------------------------------

    /** The owner's branch details (PRD BB-2, ST-7, ST-13). */
    public StoreDetails updateBranch(String storeId, UpdateBranch update, long expectedVersion, String userId) {
        Map<String, String> errors = new LinkedHashMap<>();
        optionalText(errors, "branchName", update.branchName(), 60);
        validate(errors, update.address(), update.location());
        if (update.prepMinutes() < 5 || update.prepMinutes() > 120) {
            errors.put("prepMinutes", "must be 5 to 120 minutes");
        }
        if (update.deliveryMode() == null) {
            errors.put("deliveryMode", "is required");
        }
        failIfAny(errors, "STORE_INVALID");
        StoreDocument store = findStore(storeId);
        requireVersion("branch", store.getVersion(), expectedVersion, "STORE_VERSION_CONFLICT");
        if (update.deliveryMode() == DeliveryMode.OPERATOR && !store.isOperatorDeliveryAllowed()) {
            throw new ModuleException.Conflict("The operator has not enabled operator delivery for this branch",
                    "OPERATOR_DELIVERY_NOT_ALLOWED");
        }
        store.setBranchName(blankToNull(update.branchName()));
        store.setAddress(clean(update.address()));
        store.setLocation(point(update.location()));
        store.setPrepMinutes(update.prepMinutes());
        store.setDeliveryMode(update.deliveryMode());
        store.setPickupEnabled(update.pickupEnabled());
        return saveBranch(store, userId);
    }

    /** Details only the operator edits. Withdrawing operator delivery switches the branch back to self-delivery. */
    public StoreDetails updateAdmin(String storeId, StoreAdminUpdate update, long expectedVersion, String userId) {
        Map<String, String> errors = new LinkedHashMap<>();
        requireText(errors, "legalName", update.legalName(), 1, 120);
        optionalText(errors, "taxId", update.taxId(), 40);
        failIfAny(errors, "STORE_INVALID");
        StoreDocument store = findStore(storeId);
        requireVersion("branch", store.getVersion(), expectedVersion, "STORE_VERSION_CONFLICT");
        store.setLegalName(update.legalName().trim());
        store.setTaxId(blankToNull(update.taxId()));
        store.setOperatorDeliveryAllowed(update.operatorDeliveryAllowed());
        if (!update.operatorDeliveryAllowed() && store.getDeliveryMode() == DeliveryMode.OPERATOR) {
            store.setDeliveryMode(DeliveryMode.SELF);
        }
        return saveBranch(store, userId);
    }

    /** Weekly opening hours and holiday closures (PRD ST-2). */
    public StoreDetails updateHours(String storeId, Map<DayOfWeek, List<TimeRange>> hours, List<Closure> closures,
            long expectedVersion, String userId) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<DayOfWeek, List<TimeRange>> cleanHours = new EnumMap<>(DayOfWeek.class);
        if (hours != null) {
            hours.forEach((day, ranges) -> {
                if (day == null || ranges == null) {
                    return;
                }
                if (ranges.size() > MAX_RANGES_PER_DAY) {
                    errors.put("openingHours." + day, "at most " + MAX_RANGES_PER_DAY + " ranges per day");
                }
                for (TimeRange range : ranges) {
                    if (range == null || !OpeningHours.isTime(range.open()) || !OpeningHours.isTime(range.close())
                            || range.open().equals(range.close())) {
                        errors.put("openingHours." + day, "ranges need different HH:mm open and close times");
                    }
                }
                if (!ranges.isEmpty()) {
                    cleanHours.put(day, ranges.stream()
                        .sorted(java.util.Comparator.comparing(TimeRange::open))
                        .toList());
                }
            });
        }
        List<Closure> cleanClosures = new ArrayList<>();
        if (closures != null) {
            if (closures.size() > 50) {
                errors.put("closures", "at most 50 closures");
            }
            for (Closure closure : closures) {
                if (closure == null || closure.from() == null || closure.to() == null || closure.to().isBefore(closure.from())) {
                    errors.put("closures", "each closure needs a from date on or before its to date");
                }
                else {
                    cleanClosures.add(new Closure(closure.from(), closure.to(), blankToNull(closure.reason())));
                }
            }
        }
        failIfAny(errors, "HOURS_INVALID");
        StoreDocument store = findStore(storeId);
        requireVersion("branch", store.getVersion(), expectedVersion, "STORE_VERSION_CONFLICT");
        store.setOpeningHours(cleanHours);
        LocalDate today = LocalDate.ofInstant(this.clock.instant(), zone());
        store.setClosures(cleanClosures.stream().filter((c) -> !c.to().isBefore(today.minusDays(1))).toList());
        return saveBranch(store, userId);
    }

    /**
     * The owner's safety switch (PRD ST-2a): closed until switched back on, whatever the timetable.
     */
    public StoreDetails setTemporarilyClosed(String storeId, boolean closed, String reason, String userId) {
        if (reason != null && reason.trim().length() > 120) {
            throw new ModuleException.BadRequest("The reason must be at most 120 characters", "STORE_INVALID");
        }
        StoreDocument store = findStore(storeId);
        Instant now = this.clock.instant();
        store.setTemporarilyClosed(closed);
        store.setClosedReason(closed ? blankToNull(reason) : null);
        store.setClosedAt(closed ? now : null);
        store.setUpdatedAt(now);
        store.setUpdatedBy(userId);
        StoreDocument saved = this.transaction.execute((status) -> {
            StoreDocument result = this.stores.save(store);
            this.outbox.append(new StoreAvailabilityChanged(storeId, closed, result.getClosedReason(), userId));
            return result;
        });
        return details(saved, findBrand(saved.getBrandId()), this.settings.getLocale());
    }

    /**
     * Makes a branch visible to customers, or hides it (operator only). Going live needs a location,
     * at least one menu item, and somewhere to deliver or pickup switched on.
     */
    public StoreDetails setStatus(String storeId, StoreStatus status, String userId) {
        StoreDocument store = findStore(storeId);
        if (status == StoreStatus.LIVE) {
            List<String> missing = readiness(store);
            if (!missing.isEmpty()) {
                throw new ModuleException.Conflict("The branch is not ready to go live", "STORE_NOT_READY",
                        Map.of("missing", missing));
            }
        }
        store.setStatus(status);
        return saveBranch(store, userId);
    }

    /** What still blocks going live: {@code LOCATION}, {@code MENU}, {@code DELIVERY_AREA}. */
    public List<String> readiness(String storeId) {
        return readiness(findStore(storeId));
    }

    private List<String> readiness(StoreDocument store) {
        List<String> missing = new ArrayList<>();
        if (store.getLocation() == null) {
            missing.add("LOCATION");
        }
        if (this.items.countByBrandIdAndDeletedAtIsNull(store.getBrandId()) == 0) {
            missing.add("MENU");
        }
        if (!store.isPickupEnabled() && store.getLocation() != null && this.geo
            .deliveryArea(new DeliveryAreaRequest(store.getId(), location(store), store.getDeliveryMode()))
            .isEmpty()) {
            missing.add("DELIVERY_AREA");
        }
        return missing;
    }

    // ---- Owners --------------------------------------------------------------------------

    public List<OwnerDetails> owners(String storeId) {
        StoreDocument store = findStore(storeId);
        List<OwnerDetails> owners = new ArrayList<>();
        for (String userId : store.getOwnerUserIds()) {
            try {
                UserView user = this.identity.getUser(userId);
                owners.add(new OwnerDetails(user.id(), user.email(), user.displayName()));
            }
            catch (ModuleException.NotFound ex) {
                owners.add(new OwnerDetails(userId, null, null));
            }
        }
        return owners;
    }

    /** Gives a person owner access to the branch and its brand (single-branch brands in M1). */
    public OwnerDetails addOwner(String storeId, NewOwner owner, String userId) {
        StoreDocument store = findStore(storeId);
        UserView user = this.identity.grantStoreAccess(new StoreAccessGrant(owner.email(), owner.displayName(),
                owner.initialPassword(), store.getBrandId(), storeId, userId));
        BrandDocument brand = findBrand(store.getBrandId());
        store.getOwnerUserIds().add(user.id());
        brand.getOwnerUserIds().add(user.id());
        Instant now = this.clock.instant();
        store.setUpdatedAt(now);
        store.setUpdatedBy(userId);
        brand.setUpdatedAt(now);
        brand.setUpdatedBy(userId);
        this.transaction.executeWithoutResult((status) -> {
            this.brands.save(brand);
            StoreDocument saved = this.stores.save(store);
            this.outbox.append(new StoreChanged(storeId, saved.getBrandId(), version(saved.getVersion()), userId));
        });
        return new OwnerDetails(user.id(), user.email(), user.displayName());
    }

    public void removeOwner(String storeId, String ownerUserId, String userId) {
        StoreDocument store = findStore(storeId);
        if (!store.getOwnerUserIds().remove(ownerUserId)) {
            return;
        }
        BrandDocument brand = findBrand(store.getBrandId());
        brand.getOwnerUserIds().remove(ownerUserId);
        store.setUpdatedAt(this.clock.instant());
        store.setUpdatedBy(userId);
        this.transaction.executeWithoutResult((status) -> {
            this.brands.save(brand);
            StoreDocument saved = this.stores.save(store);
            this.outbox.append(new StoreChanged(storeId, saved.getBrandId(), version(saved.getVersion()), userId));
        });
        this.identity.revokeStoreAccess(ownerUserId, store.getBrandId(), storeId);
    }

    // ---- Customers -----------------------------------------------------------------------

    /** Delivery charge and time from the branch to an address (PRD SE-7). */
    public DeliveryQuote quote(String storeId, GeoPoint destination) {
        StoreDocument store = findStore(storeId);
        String currency = this.settings.getLocale().currency();
        if (store.getLocation() == null) {
            return new DeliveryQuote(false, DeliveryQuoteView.OUT_OF_AREA, 0, 0, null, 0, 0, currency);
        }
        DeliveryQuoteView quote = this.geo.quote(new QuoteRequest(storeId, location(store), store.getDeliveryMode(),
                destination));
        int travel = (int) Math.ceil(quote.distanceMeters() / 1000.0 / RIDER_KMH * 60);
        return new DeliveryQuote(quote.deliverable(), quote.reason(), quote.chargeMinor(), quote.minOrderMinor(),
                quote.freeDeliveryAboveMinor(), quote.distanceMeters(), store.getPrepMinutes() + travel, currency);
    }

    // ---- Helpers -------------------------------------------------------------------------

    StoreDocument findStore(String storeId) {
        return this.stores.findById(storeId)
            .orElseThrow(() -> new ModuleException.NotFound("Branch " + storeId + " not found", "STORE_NOT_FOUND"));
    }

    BrandDocument findBrand(String brandId) {
        return this.brands.findById(brandId)
            .orElseThrow(() -> new ModuleException.NotFound("Brand " + brandId + " not found", "BRAND_NOT_FOUND"));
    }

    private StoreDetails saveBranch(StoreDocument store, String userId) {
        store.setUpdatedAt(this.clock.instant());
        store.setUpdatedBy(userId);
        StoreDocument saved = this.transaction.execute((status) -> {
            StoreDocument result = this.stores.save(store);
            this.outbox.append(new StoreChanged(result.getId(), result.getBrandId(), version(result.getVersion()), userId));
            return result;
        });
        return details(saved, findBrand(saved.getBrandId()), this.settings.getLocale());
    }

    private void releaseIfReplaced(String oldId, String newId, MediaOwner owner) {
        if (oldId != null && !oldId.equals(newId)) {
            this.media.release(oldId, owner);
        }
    }

    StoreDetails details(StoreDocument store, BrandDocument brand, LocaleView locale) {
        ZoneId zone = zone(locale);
        boolean open = store.getStatus() == StoreStatus.LIVE && OpeningHours.isOpen(store.getOpeningHours(),
                closedDays(store.getClosures()), store.isTemporarilyClosed(), this.clock.instant(), zone);
        Commission commission = (store.getCommissionOverride() != null) ? store.getCommissionOverride() : brand.getCommission();
        return new StoreDetails(store.getId(), brandDetails(brand), store.getBranchName(), store.getLegalName(),
                store.getTaxId(), Set.copyOf(store.getOwnerUserIds()), store.getAddress(), location(store),
                Map.copyOf(store.getOpeningHours()), List.copyOf(store.getClosures()), store.isTemporarilyClosed(),
                store.getClosedReason(), store.getPrepMinutes(), store.getDeliveryMode(), store.isOperatorDeliveryAllowed(),
                store.isPickupEnabled(), commission, store.getStatus(), zone.getId(), open, version(store.getVersion()),
                store.getCreatedAt(), store.getUpdatedAt());
    }

    static BrandDetails brandDetails(BrandDocument brand) {
        return new BrandDetails(brand.getId(), brand.getName(), Map.copyOf(brand.getDescription()),
                List.copyOf(brand.getCuisines()), brand.getLogoMediaId(), Map.copyOf(brand.getLogoUrls()),
                brand.getCoverMediaId(), Map.copyOf(brand.getCoverUrls()), brand.getCommission(), brand.getBranchCount(),
                version(brand.getVersion()), brand.getUpdatedAt());
    }

    static List<OpeningHours.ClosedDays> closedDays(List<Closure> closures) {
        return closures.stream().map((c) -> new OpeningHours.ClosedDays(c.from(), c.to())).toList();
    }

    static GeoPoint location(StoreDocument store) {
        return (store.getLocation() != null) ? new GeoPoint(store.getLocation().getY(), store.getLocation().getX()) : null;
    }

    private ZoneId zone() {
        return zone(this.settings.getLocale());
    }

    static ZoneId zone(LocaleView locale) {
        try {
            return ZoneId.of(locale.timezone());
        }
        catch (RuntimeException ex) {
            return ZoneId.of("UTC");
        }
    }

    private static Map<DayOfWeek, List<TimeRange>> defaultHours() {
        Map<DayOfWeek, List<TimeRange>> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, List.of(new TimeRange("11:00", "23:00")));
        }
        return hours;
    }

    private static Map<String, String> urls(MediaAssetView asset) {
        Map<String, String> urls = new LinkedHashMap<>();
        asset.variants().forEach((name, variant) -> urls.put(name, variant.url()));
        return urls;
    }

    private static GeoJsonPoint point(GeoPoint location) {
        return (location != null) ? new GeoJsonPoint(location.lng(), location.lat()) : null;
    }

    private static Address clean(Address address) {
        return new Address(address.line1().trim(), blankToNull(address.area()), address.city().trim(),
                blankToNull(address.postalCode()));
    }

    private static List<String> cuisines(List<String> cuisines, Map<String, String> errors) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (cuisines != null) {
            for (String cuisine : cuisines) {
                if (cuisine == null || !CUISINE.matcher(cuisine.trim()).matches()) {
                    errors.put("cuisines", "each cuisine is 1 to 30 letters, digits or spaces");
                    break;
                }
                clean.add(cuisine.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (clean.size() > 10) {
            errors.put("cuisines", "at most 10 cuisines");
        }
        return new ArrayList<>(clean);
    }

    private static void validate(Map<String, String> errors, Address address, GeoPoint location) {
        if (address == null) {
            errors.put("address", "is required");
        }
        else {
            requireText(errors, "address.line1", address.line1(), 1, 160);
            requireText(errors, "address.city", address.city(), 1, 80);
            optionalText(errors, "address.area", address.area(), 80);
            optionalText(errors, "address.postalCode", address.postalCode(), 20);
        }
        if (location != null && !location.isValid()) {
            errors.put("location", "must be a valid point");
        }
    }

    private static void validate(Map<String, String> errors, Commission commission) {
        if (commission == null) {
            errors.put("commission", "is required");
        }
        else {
            if (commission.percentBasisPoints() < 0 || commission.percentBasisPoints() > 10_000) {
                errors.put("commission.percentBasisPoints", "must be 0 to 10000 (0% to 100%)");
            }
            if (commission.fixedMinor() < 0) {
                errors.put("commission.fixedMinor", "must not be negative");
            }
        }
    }

    private static void requireText(Map<String, String> errors, String field, String value, int min, int max) {
        if (value == null || value.isBlank() || value.trim().length() < min || value.trim().length() > max) {
            errors.put(field, "must be " + min + " to " + max + " characters");
        }
    }

    private static void optionalText(Map<String, String> errors, String field, String value, int max) {
        if (value != null && value.trim().length() > max) {
            errors.put(field, "must be at most " + max + " characters");
        }
    }

    static void failIfAny(Map<String, String> errors, String code) {
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The request has invalid fields", code, Map.of("errors", errors));
        }
    }

    static void requireVersion(String what, Long current, long expected, String code) {
        if (version(current) != expected) {
            throw new ModuleException.Conflict("The " + what + " was changed by someone else; reload and retry", code,
                    Map.of("currentVersion", version(current)));
        }
    }

    static long version(Long version) {
        return (version != null) ? version : 0L;
    }

    static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
