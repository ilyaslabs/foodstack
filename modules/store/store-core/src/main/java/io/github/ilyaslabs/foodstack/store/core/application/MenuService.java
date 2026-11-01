package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.events.ItemAvailabilityChanged;
import io.github.ilyaslabs.foodstack.store.api.events.MenuChanged;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.ItemOverrideDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.ItemOverrideRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuSectionDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuSectionRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * One menu per brand (PRD ST-3 to ST-5, BB-3): sections, items with translations, modifier groups,
 * and each branch's sold-out marks. Customers read a branch's effective menu from the cache.
 */
public class MenuService {

    /** Dietary tags the apps know how to show. */
    public static final Set<String> DIETARY_TAGS = Set.of("vegetarian", "vegan", "halal", "gluten-free", "dairy-free",
            "spicy", "contains-nuts");

    private static final int MAX_SECTIONS = 50;

    private static final int MAX_ITEMS = 500;

    private final MenuSectionRepository sections;

    private final MenuItemRepository items;

    private final ItemOverrideRepository overrides;

    private final StoreRepository stores;

    private final BrandRepository brands;

    private final SettingsApi settings;

    private final MediaApi media;

    private final MenuCache cache;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public MenuService(MenuSectionRepository sections, MenuItemRepository items, ItemOverrideRepository overrides,
            StoreRepository stores, BrandRepository brands, SettingsApi settings, MediaApi media, MenuCache cache,
            OutboxWriter outbox, TransactionTemplate transaction, Clock clock) {
        this.sections = sections;
        this.items = items;
        this.overrides = overrides;
        this.stores = stores;
        this.brands = brands;
        this.settings = settings;
        this.media = media;
        this.cache = cache;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    // ---- Editor --------------------------------------------------------------------------

    public MenuDetails editorMenu(String brandId) {
        requireBrand(brandId);
        LocaleView locale = this.settings.getLocale();
        Map<String, List<MenuDetails.Item>> bySection = new LinkedHashMap<>();
        for (MenuItemDocument item : this.items.findByBrandIdAndDeletedAtIsNullOrderBySortOrderAsc(brandId)) {
            bySection.computeIfAbsent(item.getSectionId(), (key) -> new ArrayList<>()).add(itemDetails(item));
        }
        List<MenuDetails.Section> result = this.sections.findByBrandIdOrderBySortOrderAsc(brandId)
            .stream()
            .map((s) -> new MenuDetails.Section(s.getId(), Map.copyOf(s.getName()), s.getSortOrder(),
                    StoreService.version(s.getVersion()), bySection.getOrDefault(s.getId(), List.of())))
            .toList();
        return new MenuDetails(brandId, locale.currency(), locale.languages(), result);
    }

    public MenuDetails.Section createSection(String brandId, Map<String, String> name, String userId) {
        requireBrand(brandId);
        LocaleView locale = this.settings.getLocale();
        Map<String, String> errors = new LinkedHashMap<>();
        Translations.validate("name", name, locale.languages(), locale.defaultLanguage(), true, 60, errors);
        StoreService.failIfAny(errors, "MENU_INVALID");
        long count = this.sections.countByBrandId(brandId);
        if (count >= MAX_SECTIONS) {
            throw new ModuleException.Conflict("A menu can have at most " + MAX_SECTIONS + " sections", "MENU_LIMIT");
        }
        Instant now = this.clock.instant();
        MenuSectionDocument section = new MenuSectionDocument();
        section.setId(Ids.newId());
        section.setBrandId(brandId);
        section.setName(Translations.clean(name, locale.languages()));
        section.setSortOrder((int) count);
        section.setCreatedAt(now);
        section.setCreatedBy(userId);
        section.setUpdatedAt(now);
        section.setUpdatedBy(userId);
        MenuSectionDocument saved = inMenuTransaction(brandId, userId, () -> this.sections.insert(section));
        return new MenuDetails.Section(saved.getId(), Map.copyOf(saved.getName()), saved.getSortOrder(),
                StoreService.version(saved.getVersion()), List.of());
    }

    public MenuDetails.Section updateSection(String brandId, String sectionId, Map<String, String> name, int sortOrder,
            long expectedVersion, String userId) {
        LocaleView locale = this.settings.getLocale();
        Map<String, String> errors = new LinkedHashMap<>();
        Translations.validate("name", name, locale.languages(), locale.defaultLanguage(), true, 60, errors);
        StoreService.failIfAny(errors, "MENU_INVALID");
        MenuSectionDocument section = findSection(brandId, sectionId);
        StoreService.requireVersion("section", section.getVersion(), expectedVersion, "MENU_VERSION_CONFLICT");
        section.setName(Translations.clean(name, locale.languages()));
        section.setSortOrder(Math.max(0, sortOrder));
        section.setUpdatedAt(this.clock.instant());
        section.setUpdatedBy(userId);
        MenuSectionDocument saved = inMenuTransaction(brandId, userId, () -> this.sections.save(section));
        return new MenuDetails.Section(saved.getId(), Map.copyOf(saved.getName()), saved.getSortOrder(),
                StoreService.version(saved.getVersion()), List.of());
    }

    /** Deletes an empty section; move or delete its items first. */
    public void deleteSection(String brandId, String sectionId, String userId) {
        MenuSectionDocument section = findSection(brandId, sectionId);
        if (this.items.countByBrandIdAndSectionIdAndDeletedAtIsNull(brandId, sectionId) > 0) {
            throw new ModuleException.Conflict("Move or delete the section's items first", "SECTION_NOT_EMPTY");
        }
        inMenuTransaction(brandId, userId, () -> {
            this.sections.delete(section);
            return section;
        });
    }

    public MenuDetails.Item createItem(String brandId, MenuItemInput input, String userId) {
        requireBrand(brandId);
        validate(brandId, input);
        if (this.items.countByBrandIdAndDeletedAtIsNull(brandId) >= MAX_ITEMS) {
            throw new ModuleException.Conflict("A menu can have at most " + MAX_ITEMS + " items", "MENU_LIMIT");
        }
        Instant now = this.clock.instant();
        MenuItemDocument item = new MenuItemDocument();
        item.setId(Ids.newId());
        item.setBrandId(brandId);
        item.setCreatedAt(now);
        item.setCreatedBy(userId);
        apply(item, input, null, userId, now);
        MenuItemDocument saved = inMenuTransaction(brandId, userId, () -> this.items.insert(item));
        return itemDetails(saved);
    }

    public MenuDetails.Item updateItem(String brandId, String itemId, MenuItemInput input, long expectedVersion,
            String userId) {
        validate(brandId, input);
        MenuItemDocument item = findItem(brandId, itemId);
        StoreService.requireVersion("item", item.getVersion(), expectedVersion, "MENU_VERSION_CONFLICT");
        String oldImage = item.getImageMediaId();
        apply(item, input, oldImage, userId, this.clock.instant());
        MenuItemDocument saved = inMenuTransaction(brandId, userId, () -> this.items.save(item));
        if (oldImage != null && !oldImage.equals(saved.getImageMediaId())) {
            this.media.release(oldImage, owner(itemId));
        }
        return itemDetails(saved);
    }

    /** Removes an item from the menu; past orders keep their own copy (soft delete). */
    public void deleteItem(String brandId, String itemId, String userId) {
        MenuItemDocument item = findItem(brandId, itemId);
        item.setDeletedAt(this.clock.instant());
        item.setUpdatedBy(userId);
        inMenuTransaction(brandId, userId, () -> this.items.save(item));
        if (item.getImageMediaId() != null) {
            this.media.release(item.getImageMediaId(), owner(itemId));
        }
    }

    // ---- Branch availability -------------------------------------------------------------

    /** Marks an item, or one of its modifier options, sold out or available at one branch (PRD ST-4). */
    public void setSoldOut(String storeId, String itemId, String optionId, boolean soldOut, String userId) {
        StoreDocument store = this.stores.findById(storeId)
            .orElseThrow(() -> new ModuleException.NotFound("Branch " + storeId + " not found", "STORE_NOT_FOUND"));
        MenuItemDocument item = findItem(store.getBrandId(), itemId);
        String option = StoreService.blankToNull(optionId);
        if (option != null && item.getModifierGroups().stream().flatMap((g) -> g.options().stream())
            .noneMatch((o) -> o.id().equals(option))) {
            throw new ModuleException.NotFound("Option " + option + " not found", "MENU_OPTION_NOT_FOUND");
        }
        String id = storeId + ":" + itemId + ((option != null) ? ":" + option : "");
        ItemOverrideDocument override = this.overrides.findById(id).orElseGet(() -> {
            ItemOverrideDocument created = new ItemOverrideDocument();
            created.setId(id);
            created.setStoreId(storeId);
            created.setItemId(itemId);
            created.setOptionId(option);
            return created;
        });
        override.setSoldOut(soldOut);
        override.setUpdatedAt(this.clock.instant());
        override.setUpdatedBy(userId);
        this.transaction.executeWithoutResult((status) -> {
            this.overrides.save(override);
            this.outbox.append(new ItemAvailabilityChanged(storeId, itemId, option, soldOut, userId));
        });
        this.cache.evict(List.of(storeId));
    }

    // ---- Customers -----------------------------------------------------------------------

    /** The branch's menu: brand items for this branch, with its sold-out marks. */
    public EffectiveMenu effectiveMenu(String storeId) {
        return this.cache.get(storeId, () -> buildEffectiveMenu(storeId));
    }

    private EffectiveMenu buildEffectiveMenu(String storeId) {
        StoreDocument store = this.stores.findById(storeId)
            .orElseThrow(() -> new ModuleException.NotFound("Branch " + storeId + " not found", "STORE_NOT_FOUND"));
        Map<String, ItemOverrideDocument> byId = new HashMap<>();
        this.overrides.findByStoreId(storeId).forEach((o) -> byId.put(o.getId(), o));
        Map<String, List<EffectiveMenu.Item>> bySection = new LinkedHashMap<>();
        for (MenuItemDocument item : this.items.findByBrandIdAndDeletedAtIsNullOrderBySortOrderAsc(store.getBrandId())) {
            if (item.getBranchIds() != null && !item.getBranchIds().contains(storeId)) {
                continue;
            }
            ItemOverrideDocument itemOverride = byId.get(storeId + ":" + item.getId());
            long price = (itemOverride != null && itemOverride.getPriceOverrideMinor() != null)
                    ? itemOverride.getPriceOverrideMinor() : item.getPriceMinor();
            List<EffectiveMenu.Group> groups = item.getModifierGroups()
                .stream()
                .map((g) -> new EffectiveMenu.Group(g.id(), g.name(), g.minSelect(), g.maxSelect(), g.options()
                    .stream()
                    .map((o) -> {
                        ItemOverrideDocument optionOverride = byId.get(storeId + ":" + item.getId() + ":" + o.id());
                        return new EffectiveMenu.Option(o.id(), o.name(), o.priceDeltaMinor(),
                                optionOverride != null && optionOverride.isSoldOut());
                    })
                    .toList()))
                .toList();
            bySection.computeIfAbsent(item.getSectionId(), (key) -> new ArrayList<>())
                .add(new EffectiveMenu.Item(item.getId(), item.getName(), item.getDescription(), price, item.getImageUrls(),
                        item.getDietaryTags(), itemOverride != null && itemOverride.isSoldOut(), groups));
        }
        List<EffectiveMenu.Section> result = this.sections.findByBrandIdOrderBySortOrderAsc(store.getBrandId())
            .stream()
            .filter((s) -> bySection.containsKey(s.getId()))
            .map((s) -> new EffectiveMenu.Section(s.getId(), s.getName(), bySection.get(s.getId())))
            .toList();
        return new EffectiveMenu(storeId, store.getBrandId(), this.settings.getLocale().currency(), result);
    }

    // ---- Helpers -------------------------------------------------------------------------

    private <T> T inMenuTransaction(String brandId, String userId, java.util.function.Supplier<T> write) {
        T result = this.transaction.execute((status) -> {
            T value = write.get();
            this.outbox.append(new MenuChanged(brandId, userId));
            return value;
        });
        this.cache.evict(this.stores.findByBrandId(brandId).stream().map(StoreDocument::getId).toList());
        return result;
    }

    private void apply(MenuItemDocument item, MenuItemInput input, String oldImage, String userId, Instant now) {
        List<String> languages = this.settings.getLocale().languages();
        item.setSectionId(input.sectionId());
        item.setName(Translations.clean(input.name(), languages));
        item.setDescription(Translations.clean(input.description(), languages));
        item.setPriceMinor(input.priceMinor());
        String image = StoreService.blankToNull(input.imageMediaId());
        if (image != null && !image.equals(oldImage)) {
            Map<String, String> urls = new LinkedHashMap<>();
            this.media.claim(image, MediaPurpose.MENU_ITEM, owner(item.getId()))
                .variants()
                .forEach((name, variant) -> urls.put(name, variant.url()));
            item.setImageUrls(urls);
        }
        else if (image == null) {
            item.setImageUrls(new LinkedHashMap<>());
        }
        item.setImageMediaId(image);
        item.setDietaryTags((input.dietaryTags() != null)
                ? new ArrayList<>(new LinkedHashSet<>(input.dietaryTags().stream().map((t) -> t.toLowerCase(Locale.ROOT)).toList()))
                : new ArrayList<>());
        List<ModifierGroup> groups = new ArrayList<>();
        if (input.modifierGroups() != null) {
            for (ModifierGroup group : input.modifierGroups()) {
                List<ModifierOption> options = group.options()
                    .stream()
                    .map((o) -> new ModifierOption((o.id() != null && !o.id().isBlank()) ? o.id() : Ids.newId(),
                            Translations.clean(o.name(), languages), o.priceDeltaMinor()))
                    .toList();
                groups.add(new ModifierGroup((group.id() != null && !group.id().isBlank()) ? group.id() : Ids.newId(),
                        Translations.clean(group.name(), languages), group.minSelect(), group.maxSelect(), options));
            }
        }
        item.setModifierGroups(groups);
        item.setBranchIds((input.branchIds() != null && !input.branchIds().isEmpty()) ? Set.copyOf(input.branchIds()) : null);
        item.setSortOrder(Math.max(0, input.sortOrder()));
        item.setUpdatedAt(now);
        item.setUpdatedBy(userId);
    }

    private void validate(String brandId, MenuItemInput input) {
        LocaleView locale = this.settings.getLocale();
        List<String> languages = locale.languages();
        String defaultLanguage = locale.defaultLanguage();
        Map<String, String> errors = new LinkedHashMap<>();
        if (input.sectionId() == null || this.sections.findById(input.sectionId())
            .filter((s) -> s.getBrandId().equals(brandId))
            .isEmpty()) {
            errors.put("sectionId", "must be a section of this menu");
        }
        Translations.validate("name", input.name(), languages, defaultLanguage, true, 80, errors);
        Translations.validate("description", input.description(), languages, defaultLanguage, false, 300, errors);
        if (input.priceMinor() < 0 || input.priceMinor() > 100_000_000L) {
            errors.put("priceMinor", "must be 0 or more");
        }
        if (input.dietaryTags() != null && input.dietaryTags().stream()
            .anyMatch((t) -> t == null || !DIETARY_TAGS.contains(t.toLowerCase(Locale.ROOT)))) {
            errors.put("dietaryTags", "must be among " + DIETARY_TAGS.stream().sorted().toList());
        }
        if (input.modifierGroups() != null) {
            if (input.modifierGroups().size() > 10) {
                errors.put("modifierGroups", "at most 10 groups");
            }
            for (int i = 0; i < input.modifierGroups().size(); i++) {
                ModifierGroup group = input.modifierGroups().get(i);
                String prefix = "modifierGroups[" + i + "].";
                if (group == null) {
                    errors.put(prefix, "is empty");
                    continue;
                }
                Translations.validate(prefix + "name", group.name(), languages, defaultLanguage, true, 60, errors);
                int options = (group.options() != null) ? group.options().size() : 0;
                if (options == 0 || options > 30) {
                    errors.put(prefix + "options", "needs 1 to 30 options");
                }
                if (group.minSelect() < 0 || group.maxSelect() < 1 || group.minSelect() > group.maxSelect()
                        || group.maxSelect() > Math.max(options, 1)) {
                    errors.put(prefix + "maxSelect", "needs 0 <= minSelect <= maxSelect <= number of options");
                }
                if (group.options() != null) {
                    for (int j = 0; j < group.options().size(); j++) {
                        ModifierOption option = group.options().get(j);
                        String optionPrefix = prefix + "options[" + j + "].";
                        if (option == null) {
                            errors.put(optionPrefix, "is empty");
                            continue;
                        }
                        Translations.validate(optionPrefix + "name", option.name(), languages, defaultLanguage, true, 60,
                                errors);
                        if (option.priceDeltaMinor() < 0) {
                            errors.put(optionPrefix + "priceDeltaMinor", "must not be negative");
                        }
                    }
                }
            }
        }
        if (input.branchIds() != null && !input.branchIds().isEmpty()) {
            Set<String> branches = new java.util.HashSet<>(
                    this.stores.findByBrandId(brandId).stream().map(StoreDocument::getId).toList());
            if (!branches.containsAll(input.branchIds())) {
                errors.put("branchIds", "must be branches of this brand");
            }
        }
        StoreService.failIfAny(errors, "MENU_INVALID");
    }

    private void requireBrand(String brandId) {
        if (!this.brands.existsById(brandId)) {
            throw new ModuleException.NotFound("Brand " + brandId + " not found", "BRAND_NOT_FOUND");
        }
    }

    private MenuSectionDocument findSection(String brandId, String sectionId) {
        return this.sections.findById(sectionId)
            .filter((s) -> s.getBrandId().equals(brandId))
            .orElseThrow(() -> new ModuleException.NotFound("Section " + sectionId + " not found", "MENU_SECTION_NOT_FOUND"));
    }

    private MenuItemDocument findItem(String brandId, String itemId) {
        return this.items.findById(itemId)
            .filter((i) -> i.getBrandId().equals(brandId) && i.getDeletedAt() == null)
            .orElseThrow(() -> new ModuleException.NotFound("Item " + itemId + " not found", "MENU_ITEM_NOT_FOUND"));
    }

    private static MediaOwner owner(String itemId) {
        return new MediaOwner("store", "item-" + itemId);
    }

    private static MenuDetails.Item itemDetails(MenuItemDocument item) {
        return new MenuDetails.Item(item.getId(), item.getSectionId(), Map.copyOf(item.getName()),
                Map.copyOf(item.getDescription()), item.getPriceMinor(), item.getImageMediaId(),
                Map.copyOf(item.getImageUrls()), List.copyOf(item.getDietaryTags()), List.copyOf(item.getModifierGroups()),
                (item.getBranchIds() != null) ? Set.copyOf(item.getBranchIds()) : null, item.getSortOrder(),
                StoreService.version(item.getVersion()));
    }
}
