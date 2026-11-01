package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.OrderLineRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.LineProblem;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.PricedLine;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.PricedOption;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;

/**
 * Prices cart lines against a branch's current menu (PRD CO-6). Uses the cached effective menu, so
 * branch prices and sold-out marks apply exactly as the customer saw them on the store page.
 */
public class OrderValidationService {

    /** Most of one item per line. */
    public static final int MAX_QUANTITY = 50;

    /** Most lines per order. */
    public static final int MAX_LINES = 100;

    private final StoreService stores;

    private final MenuService menus;

    private final SettingsApi settings;

    public OrderValidationService(StoreService stores, MenuService menus, SettingsApi settings) {
        this.stores = stores;
        this.menus = menus;
        this.settings = settings;
    }

    public OrderValidationView validate(OrderValidationRequest request) {
        List<OrderLineRequest> lines = (request.lines() != null) ? request.lines() : List.of();
        if (lines.size() > MAX_LINES) {
            throw new ModuleException.BadRequest("An order has at most " + MAX_LINES + " lines", "TOO_MANY_LINES");
        }
        StoreDocument store = this.stores.findStore(request.storeId());
        BrandDocument brand = this.stores.findBrand(store.getBrandId());
        LocaleView locale = this.settings.getLocale();
        StoreDetails details = this.stores.details(store, brand, locale);
        EffectiveMenu menu = this.menus.effectiveMenu(store.getId());
        Map<String, EffectiveMenu.Item> items = new HashMap<>();
        menu.sections().forEach((section) -> section.items().forEach((item) -> items.put(item.id(), item)));

        List<PricedLine> priced = new ArrayList<>();
        List<LineProblem> problems = new ArrayList<>();
        long subtotal = 0;
        for (int index = 0; index < lines.size(); index++) {
            OrderLineRequest line = lines.get(index);
            EffectiveMenu.Item item = items.get(line.itemId());
            if (item == null) {
                problems.add(new LineProblem(index, line.itemId(), OrderValidationView.ITEM_NOT_FOUND, null));
                priced.add(new PricedLine(line.itemId(), Map.of(), Math.max(line.quantity(), 0), 0, List.of(), 0));
                continue;
            }
            if (item.soldOut()) {
                problems.add(new LineProblem(index, item.id(), OrderValidationView.ITEM_SOLD_OUT, null));
            }
            int quantity = line.quantity();
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                problems.add(new LineProblem(index, item.id(), OrderValidationView.QUANTITY_INVALID, null));
                quantity = Math.clamp(quantity, 1, MAX_QUANTITY);
            }
            Set<String> chosen = new LinkedHashSet<>(Objects.requireNonNullElse(line.optionIds(), List.of()));
            Set<String> known = new HashSet<>();
            List<PricedOption> options = new ArrayList<>();
            long unit = item.priceMinor();
            for (EffectiveMenu.Group group : item.modifierGroups()) {
                int count = 0;
                for (EffectiveMenu.Option option : group.options()) {
                    known.add(option.id());
                    if (!chosen.contains(option.id())) {
                        continue;
                    }
                    count++;
                    if (option.soldOut()) {
                        problems.add(new LineProblem(index, item.id(), OrderValidationView.OPTION_SOLD_OUT, option.id()));
                    }
                    options.add(new PricedOption(group.id(), group.name(), option.id(), option.name(), option.priceDeltaMinor()));
                    unit += option.priceDeltaMinor();
                }
                if (count < group.minSelect() || count > group.maxSelect()) {
                    problems.add(new LineProblem(index, item.id(), OrderValidationView.CHOICES_INVALID, group.id()));
                }
            }
            for (String optionId : chosen) {
                if (!known.contains(optionId)) {
                    problems.add(new LineProblem(index, item.id(), OrderValidationView.OPTION_NOT_FOUND, optionId));
                }
            }
            long total = Math.multiplyExact(unit, quantity);
            subtotal = Math.addExact(subtotal, total);
            priced.add(new PricedLine(item.id(), item.name(), quantity, unit, List.copyOf(options), total));
        }

        String addressLine = (store.getAddress() != null) ? Stream
            .of(store.getAddress().line1(), store.getAddress().area(), store.getAddress().city())
            .filter((part) -> part != null && !part.isBlank())
            .collect(Collectors.joining(", ")) : null;
        OrderValidationView.StoreSnapshot snapshot = new OrderValidationView.StoreSnapshot(store.getId(), store.getSlug(),
                brand.getId(), brand.getName(), store.getBranchName(), Map.copyOf(brand.getLogoUrls()), details.location(),
                addressLine, (store.getAddress() != null) ? store.getAddress().city() : null, store.getDeliveryMode(), store.isPickupEnabled(), store.getStatus() == StoreStatus.LIVE,
                details.openNow(), store.getPrepMinutes(), details.commission().percentBasisPoints(),
                details.commission().fixedMinor(), Set.copyOf(store.getOwnerUserIds()), details.timezone());
        return new OrderValidationView(snapshot, menu.currency(), List.copyOf(priced), subtotal, List.copyOf(problems));
    }
}
