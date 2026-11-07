package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.events.MenuChanged;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.ItemOverrideDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.ItemOverrideRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.PriceChangeDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.PriceChangeRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Branch prices and price approval (PRD BB-4, ST-6). A branch may set its own price for a brand
 * item or modifier option. When the operator switches price approval on, a price change by anyone
 * but operator staff waits as a {@link PriceChangeDocument} until staff approve it; the old price
 * stays on the menu meanwhile. A new item or option is not a price change: it starts with the
 * price it was created with.
 */
public class MenuPrices {

    /** Operator staff, who change prices without approval and decide on requests. */
    public static final String OPERATOR_PERMISSION = "store:manage";

    static final long MAX_PRICE = 100_000_000L;

    private static final int MAX_LISTED = 200;

    private final MenuItemRepository items;

    private final ItemOverrideRepository overrides;

    private final PriceChangeRepository changes;

    private final StoreRepository stores;

    private final BrandRepository brands;

    private final SettingsApi settings;

    private final MenuCache cache;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public MenuPrices(MenuItemRepository items, ItemOverrideRepository overrides, PriceChangeRepository changes,
            StoreRepository stores, BrandRepository brands, SettingsApi settings, MenuCache cache, OutboxWriter outbox,
            TransactionTemplate transaction, Clock clock) {
        this.items = items;
        this.overrides = overrides;
        this.changes = changes;
        this.stores = stores;
        this.brands = brands;
        this.settings = settings;
        this.cache = cache;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Whether the current user's price changes wait for approval. */
    public boolean approvalNeeded() {
        return !CurrentUser.hasAuthority(OPERATOR_PERMISSION) && !ModuleAccess.isService()
                && this.settings.getOrdering().priceApprovalRequired();
    }

    // ---- Brand menu prices -------------------------------------------------------------------

    /** An item edit with its price changes held back, and the requests to save with it. */
    record Held(MenuItemInput input, List<PriceChangeDocument> requests) {
    }

    /**
     * Keeps the current prices in an edit of an existing item and turns each changed price into a
     * request, when approval is needed; otherwise returns the edit as it is.
     */
    Held hold(MenuItemDocument item, MenuItemInput input, String userId) {
        if (!approvalNeeded()) {
            return new Held(input, List.of());
        }
        Instant now = this.clock.instant();
        List<PriceChangeDocument> requests = new ArrayList<>();
        long price = input.priceMinor();
        if (price != item.getPriceMinor()) {
            requests.add(request(item.getBrandId(), null, item.getId(), null, item.getPriceMinor(), price, userId, now));
            price = item.getPriceMinor();
        }
        Map<String, Long> current = new HashMap<>();
        item.getModifierGroups().forEach((g) -> g.options().forEach((o) -> current.put(o.id(), o.priceDeltaMinor())));
        List<ModifierGroup> groups = null;
        if (input.modifierGroups() != null) {
            groups = new ArrayList<>();
            for (ModifierGroup group : input.modifierGroups()) {
                List<ModifierOption> options = new ArrayList<>();
                for (ModifierOption option : group.options()) {
                    Long was = (option.id() != null) ? current.get(option.id()) : null;
                    if (was != null && was != option.priceDeltaMinor()) {
                        requests.add(request(item.getBrandId(), null, item.getId(), option.id(), was,
                                option.priceDeltaMinor(), userId, now));
                        options.add(new ModifierOption(option.id(), option.name(), was));
                    }
                    else {
                        options.add(option);
                    }
                }
                groups.add(new ModifierGroup(group.id(), group.name(), group.minSelect(), group.maxSelect(), options));
            }
        }
        MenuItemInput held = new MenuItemInput(input.sectionId(), input.name(), input.description(), price,
                input.imageMediaId(), input.dietaryTags(), groups, input.branchIds(), input.sortOrder());
        return new Held(held, requests);
    }

    /** Saves held requests; call inside the menu transaction. */
    void saveRequests(List<PriceChangeDocument> requests) {
        requests.forEach(this.changes::save);
    }

    // ---- Branch prices ---------------------------------------------------------------------

    /** A branch's own price for an item, or for an option's price difference. */
    public record BranchPrice(String itemId, String optionId, long priceMinor) {
    }

    /** The branch's own prices and its price changes waiting for approval. */
    public record BranchPrices(List<BranchPrice> prices, List<PriceChange> pending) {
    }

    /** What happened to a price change. */
    public enum Outcome {
        APPLIED, PENDING
    }

    public BranchPrices branchPrices(String storeId) {
        StoreDocument store = findStore(storeId);
        List<BranchPrice> prices = this.overrides.findByStoreId(storeId)
            .stream()
            .filter((o) -> o.getPriceOverrideMinor() != null)
            .map((o) -> new BranchPrice(o.getItemId(), o.getOptionId(), o.getPriceOverrideMinor()))
            .toList();
        List<PriceChange> pending = views(this.changes.findByBrandIdAndStatus(store.getBrandId(), PriceChangeDocument.PENDING)
            .stream()
            .filter((c) -> storeId.equals(c.storeId()))
            .toList());
        return new BranchPrices(prices, pending);
    }

    /**
     * Sets the branch's own price for an item, or for one of its options ({@code optionId});
     * {@code priceMinor} {@code null} goes back to the brand price.
     */
    public Outcome setBranchPrice(String storeId, String itemId, String optionId, Long priceMinor, String userId) {
        StoreDocument store = findStore(storeId);
        MenuItemDocument item = this.items.findById(itemId)
            .filter((i) -> i.getBrandId().equals(store.getBrandId()) && i.getDeletedAt() == null)
            .orElseThrow(() -> new ModuleException.NotFound("Item " + itemId + " not found", "MENU_ITEM_NOT_FOUND"));
        String option = StoreService.blankToNull(optionId);
        long brandPrice = item.getPriceMinor();
        if (option != null) {
            brandPrice = item.getModifierGroups()
                .stream()
                .flatMap((g) -> g.options().stream())
                .filter((o) -> o.id().equals(option))
                .findFirst()
                .orElseThrow(() -> new ModuleException.NotFound("Option " + option + " not found", "MENU_OPTION_NOT_FOUND"))
                .priceDeltaMinor();
        }
        if (priceMinor != null && (priceMinor < 0 || priceMinor > MAX_PRICE)) {
            throw new ModuleException.BadRequest("Enter a price of 0 or more", "MENU_INVALID");
        }
        String overrideId = storeId + ":" + itemId + ((option != null) ? ":" + option : "");
        ItemOverrideDocument existing = this.overrides.findById(overrideId).orElse(null);
        long current = (existing != null && existing.getPriceOverrideMinor() != null) ? existing.getPriceOverrideMinor()
                : brandPrice;
        Long wanted = (priceMinor != null && priceMinor == brandPrice) ? null : priceMinor;
        if (approvalNeeded()) {
            PriceChangeDocument request = request(store.getBrandId(), storeId, itemId, option, current, wanted, userId,
                    this.clock.instant());
            this.changes.save(request);
            return Outcome.PENDING;
        }
        applyBranchPrice(store, itemId, option, wanted, userId);
        return Outcome.APPLIED;
    }

    private void applyBranchPrice(StoreDocument store, String itemId, String option, Long priceMinor, String userId) {
        String overrideId = store.getId() + ":" + itemId + ((option != null) ? ":" + option : "");
        ItemOverrideDocument override = this.overrides.findById(overrideId).orElseGet(() -> {
            ItemOverrideDocument created = new ItemOverrideDocument();
            created.setId(overrideId);
            created.setStoreId(store.getId());
            created.setItemId(itemId);
            created.setOptionId(option);
            return created;
        });
        override.setPriceOverrideMinor(priceMinor);
        override.setUpdatedAt(this.clock.instant());
        override.setUpdatedBy(userId);
        this.transaction.executeWithoutResult((status) -> {
            this.overrides.save(override);
            this.outbox.append(new MenuChanged(store.getBrandId(), userId));
        });
        this.cache.evict(List.of(store.getId()));
    }

    // ---- Approvals (operator staff) ----------------------------------------------------------

    /**
     * A price change waiting for approval, with names to show.
     *
     * @param id request id
     * @param brandId brand
     * @param brandName brand name
     * @param storeId branch for a branch price, or {@code null} for the brand menu
     * @param branchName branch name, or {@code null}
     * @param itemId item
     * @param itemName item name by language
     * @param optionId option, or {@code null}
     * @param optionName option name by language, or {@code null}
     * @param currentMinor price now
     * @param proposedMinor asked price, {@code null} for "back to the brand price"
     * @param requestedBy who asked
     * @param requestedAt when
     * @param status {@code PENDING}, {@code APPROVED} or {@code REJECTED}
     */
    public record PriceChange(String id, String brandId, String brandName, String storeId, String branchName, String itemId,
            Map<String, String> itemName, String optionId, Map<String, String> optionName, long currentMinor,
            Long proposedMinor, String requestedBy, Instant requestedAt, String status) {
    }

    /** Price changes of the brand and its branches waiting for approval, for the brand's menu editor. */
    public List<PriceChange> brandPending(String brandId) {
        return views(this.changes.findByBrandIdAndStatus(brandId, PriceChangeDocument.PENDING));
    }

    public List<PriceChange> pending() {
        requireOperator();
        return views(this.changes.findByStatusOrderByRequestedAtAsc(PriceChangeDocument.PENDING, PageRequest.of(0, MAX_LISTED)));
    }

    /** Approves (applies the price) or rejects a waiting change. */
    public PriceChange decide(String requestId, boolean approve, String userId) {
        requireOperator();
        PriceChangeDocument request = this.changes.findById(requestId)
            .filter((c) -> PriceChangeDocument.PENDING.equals(c.status()))
            .orElseThrow(() -> new ModuleException.NotFound("No price change " + requestId + " is waiting",
                    "PRICE_CHANGE_NOT_FOUND"));
        if (approve) {
            if (request.storeId() != null) {
                applyBranchPrice(findStore(request.storeId()), request.itemId(), request.optionId(), request.proposedMinor(),
                        userId);
            }
            else {
                applyBrandPrice(request, userId);
            }
        }
        PriceChangeDocument decided = this.changes.save(new PriceChangeDocument(request.id(), request.brandId(),
                request.storeId(), request.itemId(), request.optionId(), request.currentMinor(), request.proposedMinor(),
                approve ? PriceChangeDocument.APPROVED : PriceChangeDocument.REJECTED, request.requestedBy(),
                request.requestedAt(), userId, this.clock.instant(), request.version()));
        return views(List.of(decided)).getFirst();
    }

    private void applyBrandPrice(PriceChangeDocument request, String userId) {
        MenuItemDocument item = this.items.findById(request.itemId())
            .filter((i) -> i.getDeletedAt() == null)
            .orElseThrow(() -> new ModuleException.Conflict("The item was deleted", "MENU_ITEM_NOT_FOUND"));
        long price = (request.proposedMinor() != null) ? request.proposedMinor() : item.getPriceMinor();
        if (request.optionId() == null) {
            item.setPriceMinor(price);
        }
        else {
            item.setModifierGroups(item.getModifierGroups()
                .stream()
                .map((g) -> new ModifierGroup(g.id(), g.name(), g.minSelect(), g.maxSelect(), g.options()
                    .stream()
                    .map((o) -> o.id().equals(request.optionId()) ? new ModifierOption(o.id(), o.name(), price) : o)
                    .toList()))
                .toList());
        }
        item.setUpdatedAt(this.clock.instant());
        item.setUpdatedBy(userId);
        this.transaction.executeWithoutResult((status) -> {
            this.items.save(item);
            this.outbox.append(new MenuChanged(item.getBrandId(), userId));
        });
        this.cache.evict(this.stores.findByBrandId(item.getBrandId()).stream().map(StoreDocument::getId).toList());
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private PriceChangeDocument request(String brandId, String storeId, String itemId, String optionId, long current,
            Long proposed, String userId, Instant now) {
        String id = PriceChangeDocument.idOf(brandId, storeId, itemId, optionId);
        Long version = this.changes.findById(id).map(PriceChangeDocument::version).orElse(null);
        return new PriceChangeDocument(id, brandId, storeId, itemId, optionId, current, proposed, PriceChangeDocument.PENDING,
                userId, now, null, null, version);
    }

    private List<PriceChange> views(List<PriceChangeDocument> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<String, MenuItemDocument> itemsById = new HashMap<>();
        this.items.findAllById(found.stream().map(PriceChangeDocument::itemId).distinct().toList())
            .forEach((i) -> itemsById.put(i.getId(), i));
        Map<String, String> brandNames = new HashMap<>();
        this.brands.findAllById(found.stream().map(PriceChangeDocument::brandId).distinct().toList())
            .forEach((b) -> brandNames.put(b.getId(), b.getName()));
        Map<String, String> branchNames = new HashMap<>();
        this.stores.findAllById(found.stream().map(PriceChangeDocument::storeId).filter((s) -> s != null).distinct().toList())
            .forEach((s) -> branchNames.put(s.getId(), s.getBranchName()));
        List<PriceChange> result = new ArrayList<>();
        for (PriceChangeDocument c : found) {
            MenuItemDocument item = itemsById.get(c.itemId());
            Map<String, String> optionName = null;
            if (item != null && c.optionId() != null) {
                optionName = item.getModifierGroups()
                    .stream()
                    .flatMap((g) -> g.options().stream())
                    .filter((o) -> o.id().equals(c.optionId()))
                    .findFirst()
                    .map((o) -> Map.copyOf(o.name()))
                    .orElse(null);
            }
            result.add(new PriceChange(c.id(), c.brandId(), brandNames.get(c.brandId()), c.storeId(),
                    (c.storeId() != null) ? branchNames.get(c.storeId()) : null, c.itemId(),
                    (item != null) ? Map.copyOf(item.getName()) : new LinkedHashMap<>(), c.optionId(), optionName,
                    c.currentMinor(), c.proposedMinor(), c.requestedBy(), c.requestedAt(), c.status()));
        }
        return result;
    }

    private StoreDocument findStore(String storeId) {
        return this.stores.findById(storeId)
            .orElseThrow(() -> new ModuleException.NotFound("Branch " + storeId + " not found", "STORE_NOT_FOUND"));
    }

    private static void requireOperator() {
        if (!CurrentUser.hasAuthority(OPERATOR_PERMISSION) && !ModuleAccess.isService()) {
            throw new ModuleException.Forbidden("Only operator staff decide on price changes", "STORE_ACCESS_DENIED");
        }
    }
}
