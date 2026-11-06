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

import io.github.ilyaslabs.foodstack.geo.api.CityView;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryAreaRequest;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryQuoteView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.QuoteRequest;
import io.github.ilyaslabs.foodstack.identity.api.BrandAccessGrant;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.StoreAccessGrant;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.BranchBrands;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.api.events.BrandChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreAvailabilityChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreChanged;
import io.github.ilyaslabs.foodstack.store.core.domain.BranchTimeZones;
import io.github.ilyaslabs.foodstack.store.core.domain.CommissionSchedule;
import io.github.ilyaslabs.foodstack.store.core.domain.OpeningHours;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreSlugDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreSlugRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.dao.DuplicateKeyException;
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

    /** Branches per brand; a chain bigger than this is several brands. */
    static final int MAX_BRANCHES = 500;

    /** Owners per brand or branch. */
    static final int MAX_OWNERS = 20;

    private final BrandRepository brands;

    private final StoreRepository stores;

    private final StoreSlugRepository slugs;

    private final MenuItemRepository items;

    private final SettingsApi settings;

    private final MediaApi media;

    private final IdentityApi identity;

    private final GeoApi geo;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    private final BranchTimeZones timeZones;

    private BranchBrands branchBrands;

    public StoreService(BrandRepository brands, StoreRepository stores, StoreSlugRepository slugs, MenuItemRepository items,
            SettingsApi settings, MediaApi media, IdentityApi identity, GeoApi geo, OutboxWriter outbox,
            TransactionTemplate transaction, Clock clock) {
        this(brands, stores, slugs, items, settings, media, identity, geo, outbox, transaction, clock, null);
    }

    /**
     * @param timeZones finds a branch's time zone from its location; {@code null} keeps the deployment's
     */
    public StoreService(BrandRepository brands, StoreRepository stores, StoreSlugRepository slugs, MenuItemRepository items,
            SettingsApi settings, MediaApi media, IdentityApi identity, GeoApi geo, OutboxWriter outbox,
            TransactionTemplate transaction, Clock clock, BranchTimeZones timeZones) {
        this.timeZones = timeZones;
        this.brands = brands;
        this.stores = stores;
        this.slugs = slugs;
        this.items = items;
        this.settings = settings;
        this.media = media;
        this.identity = identity;
        this.geo = geo;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Where branch-to-brand links are published for brand owners' access checks (TDD 9.1); optional in tests. */
    public void setBranchBrands(BranchBrands branchBrands) {
        this.branchBrands = branchBrands;
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
        String cityId = cityFor(errors, command.cityId(), command.location(), null);
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
        store.setTimezone(timezoneAt(command.location(), locale).getId());
        store.setOpeningHours(defaultHours());
        store.setPrepMinutes(command.prepMinutes());
        store.setDeliveryMode(DeliveryMode.SELF);
        store.setPickupEnabled(command.pickupEnabled());
        store.setCityId(cityId);
        store.setStatus(StoreStatus.DRAFT);
        store.setCreatedAt(now);
        store.setCreatedBy(userId);
        store.setUpdatedAt(now);
        store.setUpdatedBy(userId);
        store.setSlug(freeSlug(Slugs.of(brand.getName(), store.getBranchName())));

        StoreDocument saved = claimingSlug(() -> this.transaction.execute((status) -> {
            this.slugs.insert(new StoreSlugDocument(store.getSlug(), store.getId(), now));
            BrandDocument savedBrand = this.brands.insert(brand);
            StoreDocument result = this.stores.insert(store);
            this.outbox.append(new BrandChanged(savedBrand.getId(), version(savedBrand.getVersion()), userId));
            this.outbox.append(new StoreChanged(result.getId(), result.getBrandId(), version(result.getVersion()), userId));
            return result;
        }));
        rememberBrand(saved);
        return details(saved, brand, locale);
    }

    /**
     * Adds a branch to an existing brand (PRD BB-1, BB-2), in {@code DRAFT} until the operator makes it
     * live. The brand's menu is shared at once; the branch needs its own hours, zones and riders.
     * The brand shows in the apps from its second branch on.
     */
    public StoreDetails addBranch(String brandId, NewBranch command, String userId) {
        LocaleView locale = this.settings.getLocale();
        BrandDocument brand = findBrand(brandId);
        Map<String, String> errors = new LinkedHashMap<>();
        requireText(errors, "branchName", command.branchName(), 1, 60);
        requireText(errors, "legalName", command.legalName(), 1, 120);
        optionalText(errors, "taxId", command.taxId(), 40);
        validate(errors, command.address(), command.location());
        if (command.prepMinutes() < 5 || command.prepMinutes() > 120) {
            errors.put("prepMinutes", "must be 5 to 120 minutes");
        }
        String cityId = cityFor(errors, command.cityId(), command.location(), null);
        List<StoreDocument> existing = this.stores.findByBrandId(brandId);
        if (existing.stream().anyMatch((b) -> command.branchName() != null && b.getBranchName() != null
                && b.getBranchName().equalsIgnoreCase(command.branchName().trim()))) {
            errors.put("branchName", "another branch of this brand has this name");
        }
        failIfAny(errors, "STORE_INVALID");
        if (existing.size() >= MAX_BRANCHES) {
            throw new ModuleException.Conflict("A brand can have at most " + MAX_BRANCHES + " branches", "BRANCH_LIMIT");
        }
        Instant now = this.clock.instant();
        StoreDocument store = new StoreDocument();
        store.setId(Ids.newId());
        store.setBrandId(brandId);
        store.setBrandName(brand.getName());
        store.setBranchName(command.branchName().trim());
        store.setLegalName(command.legalName().trim());
        store.setTaxId(blankToNull(command.taxId()));
        store.setAddress(clean(command.address()));
        store.setLocation(point(command.location()));
        store.setTimezone(timezoneAt(command.location(), locale).getId());
        store.setOpeningHours(defaultHours());
        store.setPrepMinutes(command.prepMinutes());
        store.setDeliveryMode(DeliveryMode.SELF);
        store.setPickupEnabled(command.pickupEnabled());
        store.setCityId(cityId);
        store.setStatus(StoreStatus.DRAFT);
        store.setCreatedAt(now);
        store.setCreatedBy(userId);
        store.setUpdatedAt(now);
        store.setUpdatedBy(userId);
        store.setSlug(freeSlug(Slugs.of(brand.getName(), store.getBranchName())));
        brand.setBranchCount(existing.size() + 1);
        brand.setUpdatedAt(now);
        brand.setUpdatedBy(userId);
        StoreDocument saved = claimingSlug(() -> this.transaction.execute((status) -> {
            this.slugs.insert(new StoreSlugDocument(store.getSlug(), store.getId(), now));
            BrandDocument savedBrand = this.brands.save(brand);
            StoreDocument result = this.stores.insert(store);
            this.outbox.append(new BrandChanged(brandId, version(savedBrand.getVersion()), userId));
            this.outbox.append(new StoreChanged(result.getId(), brandId, version(result.getVersion()), userId));
            return result;
        }));
        rememberBrand(saved);
        return details(saved, findBrand(brandId), locale);
    }

    /** The brand's branches, in the order they were added. */
    public List<StoreSummary> branches(String brandId) {
        findBrand(brandId);
        return summaries(this.stores.findByBrandId(brandId)
            .stream()
            .sorted(java.util.Comparator.comparing(StoreDocument::getId))
            .toList());
    }

    /** Publishes every branch's brand for access checks and moves owners kept on single branches to their brand. */
    public int backfillBranchAccess() {
        int moved = 0;
        String after = "";
        while (true) {
            List<StoreDocument> page = this.stores.findByIdGreaterThanOrderByIdAsc(after, Limit.of(200));
            if (page.isEmpty()) {
                return moved;
            }
            for (StoreDocument store : page) {
                rememberBrand(store);
                moved += moveBrandOwnersOffBranch(store);
            }
            after = page.getLast().getId();
        }
    }

    /**
     * Before brand owners existed (M1 to M3) the owner of a single restaurant was kept on both the
     * brand and its branch. They are the brand's owners (PRD BB-1); the branch keeps only its own.
     */
    private int moveBrandOwnersOffBranch(StoreDocument store) {
        BrandDocument brand = this.brands.findById(store.getBrandId()).orElse(null);
        if (brand == null || brand.getOwnerUserIds().stream().noneMatch(store.getOwnerUserIds()::contains)) {
            return 0;
        }
        store.getOwnerUserIds().removeAll(brand.getOwnerUserIds());
        try {
            this.stores.save(store);
            return 1;
        }
        catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            return 0;
        }
    }

    private void rememberBrand(StoreDocument store) {
        if (this.branchBrands != null) {
            try {
                this.branchBrands.remember(store.getId(), store.getBrandId());
            }
            catch (RuntimeException ex) {
                // published again at the next start-up; until then brand owners use the branch through its owners list
            }
        }
    }

    // ---- Web addresses ----------------------------------------------------------------------

    /**
     * The branch a customer link points at: its slug, or its id (the back office and API clients
     * still use ids). Unknown slugs are not found; a changed slug does not redirect.
     */
    public String resolve(String key) {
        if (Slugs.looksLikeId(key)) {
            return key;
        }
        return this.slugs.findById(key.toLowerCase(Locale.ROOT))
            .map(StoreSlugDocument::getStoreId)
            .orElseThrow(() -> new ModuleException.NotFound("Store " + key + " not found", "STORE_NOT_FOUND"));
    }

    /** Sets the branch's web address (operator only); the old slug is freed. */
    public StoreDetails updateSlug(String storeId, String slug, long expectedVersion, String userId) {
        String wanted = (slug != null) ? slug.trim().toLowerCase(Locale.ROOT) : null;
        if (!Slugs.isValid(wanted)) {
            failIfAny(Map.of("slug", "use 3 to " + Slugs.MAX_LENGTH + " lower-case letters, digits and single hyphens"),
                    "STORE_INVALID");
        }
        StoreDocument store = findStore(storeId);
        requireVersion("branch", store.getVersion(), expectedVersion, "STORE_VERSION_CONFLICT");
        if (wanted.equals(store.getSlug())) {
            return details(store, findBrand(store.getBrandId()), this.settings.getLocale());
        }
        if (this.slugs.existsById(wanted)) {
            throw slugTaken();
        }
        String old = store.getSlug();
        store.setSlug(wanted);
        return claimingSlug(() -> saveBranch(store, () -> {
            this.slugs.insert(new StoreSlugDocument(wanted, storeId, this.clock.instant()));
            if (old != null) {
                this.slugs.deleteById(old);
            }
        }, userId));
    }

    /**
     * Gives a web address to every branch that has none: branches onboarded before slugs existed
     * and the demo seeder's. Runs at start-up; safe to run on several instances at once.
     * @return how many branches got one
     */
    public int assignMissingSlugs(String userId) {
        int assigned = 0;
        for (int attempt = 0; attempt < 1000; attempt++) {
            List<StoreDocument> batch = this.stores.findBySlugIsNull(Limit.of(100));
            if (batch.isEmpty()) {
                break;
            }
            for (StoreDocument store : batch) {
                String slug = freeSlug(Slugs.of(store.getBrandName(), store.getBranchName()));
                store.setSlug(slug);
                try {
                    saveBranch(store, () -> this.slugs.insert(new StoreSlugDocument(slug, store.getId(), this.clock.instant())),
                            userId);
                    assigned++;
                }
                catch (DuplicateKeyException | org.springframework.dao.OptimisticLockingFailureException ex) {
                    // another instance took the slug or changed the branch first; the next pass retries it
                }
            }
        }
        return assigned;
    }

    /** {@code base}, or {@code base-2}, {@code base-3} and so on when taken. */
    private String freeSlug(String base) {
        for (int n = 1; n <= 50; n++) {
            String candidate = (n == 1) ? base : Slugs.numbered(base, n);
            if (!this.slugs.existsById(candidate)) {
                return candidate;
            }
        }
        return Slugs.numbered(base, 1000 + new java.util.Random().nextInt(9000));
    }

    private static <T> T claimingSlug(java.util.function.Supplier<T> write) {
        try {
            return write.get();
        }
        catch (DuplicateKeyException ex) {
            throw slugTaken();
        }
    }

    private static ModuleException slugTaken() {
        return new ModuleException.Conflict("That web address is already used by another restaurant", "SLUG_TAKEN",
                Map.of("errors", Map.of("slug", "already used by another restaurant")));
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
        return summaries(page);
    }

    /**
     * Branches a store-area user works on: their own branches plus every branch of the brands they
     * own (PRD BB-5), newest first.
     */
    public List<StoreSummary> listFor(Set<String> storeIds, Set<String> brandIds) {
        Map<String, StoreDocument> found = new LinkedHashMap<>();
        if (!storeIds.isEmpty()) {
            this.stores.findByIdInOrderByIdDesc(storeIds).forEach((s) -> found.put(s.getId(), s));
        }
        for (String brandId : brandIds) {
            this.stores.findByBrandId(brandId).forEach((s) -> found.put(s.getId(), s));
        }
        return summaries(found.values()
            .stream()
            .sorted(java.util.Comparator.comparing(StoreDocument::getId).reversed())
            .limit(500)
            .toList());
    }

    private List<StoreSummary> summaries(List<StoreDocument> page) {
        // Logos and branch counts live on the brand: one read for the whole page.
        Map<String, BrandDocument> brandsById = new HashMap<>();
        this.brands.findAllById(page.stream().map(StoreDocument::getBrandId).collect(Collectors.toSet()))
            .forEach((b) -> brandsById.put(b.getId(), b));
        return page.stream().map((s) -> {
            BrandDocument brand = brandsById.get(s.getBrandId());
            return new StoreSummary(s.getId(), s.getBrandId(), s.getBrandName(), s.getBranchName(),
                    (s.getAddress() != null) ? s.getAddress().city() : null, s.getStatus(), s.isTemporarilyClosed(),
                    s.getDeliveryMode(), s.getCreatedAt(), (brand != null) ? Map.copyOf(brand.getLogoUrls()) : Map.of(),
                    s.getCityId(), (brand != null) ? brand.getBranchCount() : 1);
        }).toList();
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

    /** Sets the brand's default commission now (operator only, PRD ST-1, BB-8). */
    public BrandDetails updateCommission(String brandId, Commission commission, String userId) {
        return updateCommission(brandId, commission, null, userId);
    }

    /**
     * Plans the brand's commission from a date (operator only, PRD FI-2, BB-8); {@code null} means
     * now. Orders take the commission in force when they are placed; a change replaces any change
     * planned on or after its date.
     */
    public BrandDetails updateCommission(String brandId, Commission commission, Instant effectiveFrom, String userId) {
        Map<String, String> errors = new LinkedHashMap<>();
        validate(errors, commission);
        Instant now = this.clock.instant();
        Instant from = effectiveFrom(errors, effectiveFrom, now);
        failIfAny(errors, "COMMISSION_INVALID");
        BrandDocument brand = findBrand(brandId);
        brand.setCommissionChanges(new ArrayList<>(CommissionSchedule.plan(brand.getCommissionChanges(),
                new CommissionChange(commission, from, userId, now))));
        brand.setUpdatedAt(now);
        brand.setUpdatedBy(userId);
        BrandDocument saved = this.transaction.execute((status) -> {
            BrandDocument result = this.brands.save(brand);
            this.outbox.append(new BrandChanged(brandId, version(result.getVersion()), userId));
            return result;
        });
        return brandDetails(saved);
    }

    /**
     * Plans a branch's own commission from a date (operator only, PRD BB-8, FI-2); a {@code null}
     * commission goes back to the brand's from that date.
     */
    public StoreDetails updateBranchCommission(String storeId, Commission commission, Instant effectiveFrom, String userId) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (commission != null) {
            validate(errors, commission);
        }
        Instant now = this.clock.instant();
        Instant from = effectiveFrom(errors, effectiveFrom, now);
        failIfAny(errors, "COMMISSION_INVALID");
        StoreDocument store = findStore(storeId);
        store.setCommissionChanges(new ArrayList<>(CommissionSchedule.plan(store.getCommissionChanges(),
                new CommissionChange(commission, from, userId, now))));
        return saveBranch(store, userId);
    }

    /** Whether branch managers may create promotions for their own branch (PRD BB-11); brand owners decide. */
    public BrandDetails setBranchPromotionsAllowed(String brandId, boolean allowed, String userId) {
        BrandDocument brand = findBrand(brandId);
        brand.setBranchPromotionsAllowed(allowed);
        brand.setUpdatedAt(this.clock.instant());
        brand.setUpdatedBy(userId);
        BrandDocument saved = this.transaction.execute((status) -> {
            BrandDocument result = this.brands.save(brand);
            this.outbox.append(new BrandChanged(brandId, version(result.getVersion()), userId));
            return result;
        });
        return brandDetails(saved);
    }

    /** The commission an order placed at {@code at} pays: the branch's own when it has one, else the brand's. */
    Commission commissionAt(StoreDocument store, BrandDocument brand, Instant at) {
        Commission own = CommissionSchedule.at(store.getCommissionOverride(), store.getCommissionChanges(), at);
        return (own != null) ? own : CommissionSchedule.at(brand.getCommission(), brand.getCommissionChanges(), at);
    }

    private static Instant effectiveFrom(Map<String, String> errors, Instant effectiveFrom, Instant now) {
        if (effectiveFrom == null) {
            return now;
        }
        if (effectiveFrom.isBefore(now.minusSeconds(60))) {
            errors.put("effectiveFrom", "must be now or later; past orders keep the commission they were placed with");
        }
        return effectiveFrom.isBefore(now) ? now : effectiveFrom;
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
        if (update.timezone() != null && !update.timezone().isBlank() && BranchTimeZones.parse(update.timezone()).isEmpty()) {
            errors.put("timezone", "must be an IANA time zone such as Asia/Karachi");
        }
        if (update.ticketPaperMm() != null && update.ticketPaperMm() != 58 && update.ticketPaperMm() != 80) {
            errors.put("ticketPaperMm", "must be 80 or 58");
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
        store.setTimezone(branchTimezone(store, update));
        store.setLocation(point(update.location()));
        store.setPrepMinutes(update.prepMinutes());
        store.setDeliveryMode(update.deliveryMode());
        store.setPickupEnabled(update.pickupEnabled());
        if (update.ticketPaperMm() != null) {
            store.setTicketPaperMm(update.ticketPaperMm());
        }
        return saveBranch(store, userId);
    }

    /** Details only the operator edits. Withdrawing operator delivery switches the branch back to self-delivery. */
    public StoreDetails updateAdmin(String storeId, StoreAdminUpdate update, long expectedVersion, String userId) {
        Map<String, String> errors = new LinkedHashMap<>();
        requireText(errors, "legalName", update.legalName(), 1, 120);
        optionalText(errors, "taxId", update.taxId(), 40);
        StoreDocument store = findStore(storeId);
        String cityId = cityFor(errors, update.cityId(), location(store), store.getCityId());
        failIfAny(errors, "STORE_INVALID");
        requireVersion("branch", store.getVersion(), expectedVersion, "STORE_VERSION_CONFLICT");
        store.setCityId(cityId);
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
        LocalDate today = LocalDate.ofInstant(this.clock.instant(), zone(store, this.settings.getLocale()));
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
            .deliveryArea(new DeliveryAreaRequest(store.getId(), location(store), store.getDeliveryMode(), store.getCityId()))
            .isEmpty()) {
            missing.add("DELIVERY_AREA");
        }
        return missing;
    }

    // ---- Owners --------------------------------------------------------------------------

    /** The branch's own owners and managers (PRD BB-5, BB-6), not the brand's owners. */
    public List<OwnerDetails> owners(String storeId) {
        return people(findStore(storeId).getOwnerUserIds());
    }

    /** The brand's owners (PRD BB-5). */
    public List<OwnerDetails> brandOwners(String brandId) {
        return people(findBrand(brandId).getOwnerUserIds());
    }

    private List<OwnerDetails> people(Set<String> userIds) {
        List<OwnerDetails> owners = new ArrayList<>();
        for (String userId : userIds) {
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

    /**
     * Gives a person access to one branch only (PRD BB-5, BB-6): a branch manager or a franchise
     * owner. They run the branch but never edit the brand menu or prices.
     */
    public OwnerDetails addOwner(String storeId, NewOwner owner, String userId) {
        StoreDocument store = findStore(storeId);
        if (store.getOwnerUserIds().size() >= MAX_OWNERS) {
            throw new ModuleException.Conflict("A branch can have at most " + MAX_OWNERS + " owners", "OWNER_LIMIT");
        }
        UserView user = this.identity.grantStoreAccess(new StoreAccessGrant(owner.email(), owner.displayName(),
                owner.initialPassword(), storeId, userId));
        store.getOwnerUserIds().add(user.id());
        Instant now = this.clock.instant();
        store.setUpdatedAt(now);
        store.setUpdatedBy(userId);
        this.transaction.executeWithoutResult((status) -> {
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
        store.setUpdatedAt(this.clock.instant());
        store.setUpdatedBy(userId);
        this.transaction.executeWithoutResult((status) -> {
            StoreDocument saved = this.stores.save(store);
            this.outbox.append(new StoreChanged(storeId, saved.getBrandId(), version(saved.getVersion()), userId));
        });
        this.identity.revokeStoreAccess(ownerUserId, storeId);
    }

    /** Gives a person brand owner access (PRD BB-5): the brand, its menu and every branch. */
    public OwnerDetails addBrandOwner(String brandId, NewOwner owner, String userId) {
        BrandDocument brand = findBrand(brandId);
        if (brand.getOwnerUserIds().size() >= MAX_OWNERS) {
            throw new ModuleException.Conflict("A brand can have at most " + MAX_OWNERS + " owners", "OWNER_LIMIT");
        }
        UserView user = this.identity.grantBrandAccess(new BrandAccessGrant(owner.email(), owner.displayName(),
                owner.initialPassword(), brandId, userId));
        brand.getOwnerUserIds().add(user.id());
        brand.setUpdatedAt(this.clock.instant());
        brand.setUpdatedBy(userId);
        saveBrandAndBranches(brand, userId);
        return new OwnerDetails(user.id(), user.email(), user.displayName());
    }

    public void removeBrandOwner(String brandId, String ownerUserId, String userId) {
        BrandDocument brand = findBrand(brandId);
        if (!brand.getOwnerUserIds().remove(ownerUserId)) {
            return;
        }
        brand.setUpdatedAt(this.clock.instant());
        brand.setUpdatedBy(userId);
        saveBrandAndBranches(brand, userId);
        this.identity.revokeBrandAccess(ownerUserId, brandId);
    }

    /** Saves the brand and tells every branch, whose order alerts go to the brand's owners too. */
    private void saveBrandAndBranches(BrandDocument brand, String userId) {
        List<StoreDocument> branches = this.stores.findByBrandId(brand.getId());
        this.transaction.executeWithoutResult((status) -> {
            BrandDocument saved = this.brands.save(brand);
            this.outbox.append(new BrandChanged(saved.getId(), version(saved.getVersion()), userId));
            for (StoreDocument branch : branches) {
                this.outbox.append(new StoreChanged(branch.getId(), branch.getBrandId(), version(branch.getVersion()), userId));
            }
        });
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
                destination, zone(store, this.settings.getLocale()).getId(), store.getCityId()));
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
        return saveBranch(store, () -> {
        }, userId);
    }

    /** Saves the branch, with {@code alsoWrite} in the same transaction. */
    private StoreDetails saveBranch(StoreDocument store, Runnable alsoWrite, String userId) {
        store.setUpdatedAt(this.clock.instant());
        store.setUpdatedBy(userId);
        StoreDocument saved = this.transaction.execute((status) -> {
            alsoWrite.run();
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
        ZoneId zone = zone(store, locale);
        boolean open = store.getStatus() == StoreStatus.LIVE && OpeningHours.isOpen(store.getOpeningHours(),
                closedDays(store.getClosures()), store.isTemporarilyClosed(), this.clock.instant(), zone);
        Instant now = this.clock.instant();
        Commission commission = commissionAt(store, brand, now);
        return new StoreDetails(store.getId(), store.getSlug(), brandDetails(brand, now), store.getBranchName(),
                store.getLegalName(), store.getTaxId(), Set.copyOf(store.getOwnerUserIds()), store.getAddress(),
                location(store), Map.copyOf(store.getOpeningHours()), List.copyOf(store.getClosures()),
                store.isTemporarilyClosed(), store.getClosedReason(), store.getPrepMinutes(), store.getDeliveryMode(),
                store.isOperatorDeliveryAllowed(), store.isPickupEnabled(), commission, store.getStatus(), zone.getId(), open,
                version(store.getVersion()), store.getCreatedAt(), store.getUpdatedAt(), store.getCityId(),
                ticketPaper(store), branchCommission(store), Set.copyOf(brand.getOwnerUserIds()));
    }

    /** The branch's own commission changes, oldest first; empty when it always used the brand's. */
    private static List<CommissionChange> branchCommission(StoreDocument store) {
        List<CommissionChange> changes = new ArrayList<>();
        if (store.getCommissionOverride() != null) {
            changes.add(new CommissionChange(store.getCommissionOverride(), Instant.EPOCH, null, null));
        }
        store.getCommissionChanges().stream()
            .sorted(java.util.Comparator.comparing(CommissionChange::effectiveFrom))
            .forEach(changes::add);
        return List.copyOf(changes);
    }

    static int ticketPaper(StoreDocument store) {
        return (store.getTicketPaperMm() != null) ? store.getTicketPaperMm() : 80;
    }

    BrandDetails brandDetails(BrandDocument brand) {
        return brandDetails(brand, this.clock.instant());
    }

    static BrandDetails brandDetails(BrandDocument brand, Instant now) {
        List<CommissionChange> changes = new ArrayList<>();
        changes.add(new CommissionChange(brand.getCommission(), Instant.EPOCH, null, null));
        brand.getCommissionChanges().stream()
            .sorted(java.util.Comparator.comparing(CommissionChange::effectiveFrom))
            .forEach(changes::add);
        return new BrandDetails(brand.getId(), brand.getName(), Map.copyOf(brand.getDescription()),
                List.copyOf(brand.getCuisines()), brand.getLogoMediaId(), Map.copyOf(brand.getLogoUrls()),
                brand.getCoverMediaId(), Map.copyOf(brand.getCoverUrls()),
                CommissionSchedule.at(brand.getCommission(), brand.getCommissionChanges(), now), brand.getBranchCount(),
                version(brand.getVersion()), brand.getUpdatedAt(), List.copyOf(changes), Set.copyOf(brand.getOwnerUserIds()),
                brand.isBranchPromotionsAllowed());
    }

    /**
     * The city a branch goes in: the one asked for (it must exist, and be active unless it is the
     * branch's current one), else the nearest active city to its location, else none.
     */
    private String cityFor(Map<String, String> errors, String requested, GeoPoint location, String current) {
        String wanted = blankToNull(requested);
        List<CityView> cities;
        try {
            cities = this.geo.cities();
        }
        catch (RuntimeException ex) {
            return (wanted != null) ? wanted : current;
        }
        if (wanted != null) {
            CityView city = cities.stream().filter((c) -> c.id().equals(wanted)).findFirst().orElse(null);
            if (city == null || (!city.active() && !wanted.equals(current))) {
                errors.put("cityId", "must be one of the operator's active cities");
            }
            return wanted;
        }
        if (current != null) {
            return current;
        }
        if (location == null || !location.isValid() || cities.isEmpty()) {
            return null;
        }
        CityView nearest = this.geo.nearestCity(location);
        return (nearest != null) ? nearest.id() : null;
    }

    static List<OpeningHours.ClosedDays> closedDays(List<Closure> closures) {
        return closures.stream().map((c) -> new OpeningHours.ClosedDays(c.from(), c.to())).toList();
    }

    static GeoPoint location(StoreDocument store) {
        return (store.getLocation() != null) ? new GeoPoint(store.getLocation().getY(), store.getLocation().getX()) : null;
    }

    /**
     * The zone the owner chose wins. Otherwise the zone is found from the location: on a new branch,
     * when the location moves, or on a branch saved before branches had their own zone.
     */
    private String branchTimezone(StoreDocument store, UpdateBranch update) {
        String chosen = BranchTimeZones.parse(update.timezone()).map(ZoneId::getId).orElse(null);
        boolean moved = !Objects.equals(location(store), update.location());
        if (chosen != null && (!chosen.equals(store.getTimezone()) || !moved)) {
            return chosen;
        }
        if (chosen == null && store.getTimezone() != null && !moved) {
            return store.getTimezone();
        }
        return timezoneAt(update.location(), this.settings.getLocale()).getId();
    }

    private ZoneId timezoneAt(GeoPoint location, LocaleView locale) {
        ZoneId deployment = zone(locale);
        if (this.timeZones == null || location == null || !location.isValid()) {
            return deployment;
        }
        try {
            return this.timeZones.zoneAt(location.lat(), location.lng(), deployment);
        }
        catch (RuntimeException ex) {
            return deployment;
        }
    }

    /** The branch's own zone, or the deployment's for a branch saved before branches had one. */
    static ZoneId zone(StoreDocument store, LocaleView locale) {
        return BranchTimeZones.parse(store.getTimezone()).orElseGet(() -> zone(locale));
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
