package io.github.ilyaslabs.foodstack.store.core.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;

import org.springframework.data.domain.Limit;

/** The listing other modules read, mainly search: a branch with its brand's fields and dish names. */
public class StoreListingService {

    private final StoreService stores;

    private final MenuService menus;

    private final StoreRepository repository;

    private final SettingsApi settings;

    public StoreListingService(StoreService stores, MenuService menus, StoreRepository repository, SettingsApi settings) {
        this.stores = stores;
        this.menus = menus;
        this.repository = repository;
        this.settings = settings;
    }

    public StoreListingView listing(String storeId) {
        StoreDocument store = this.stores.findStore(storeId);
        BrandDocument brand = this.stores.findBrand(store.getBrandId());
        LocaleView locale = this.settings.getLocale();
        EffectiveMenu menu = this.menus.effectiveMenu(storeId);
        Map<String, List<String>> dishNames = new LinkedHashMap<>();
        LinkedHashSet<String> dietary = new LinkedHashSet<>();
        for (EffectiveMenu.Section section : menu.sections()) {
            for (EffectiveMenu.Item item : section.items()) {
                if (item.soldOut()) {
                    continue;
                }
                item.name().forEach((language, name) -> dishNames.computeIfAbsent(language, (k) -> new ArrayList<>()).add(name));
                dietary.addAll(item.dietaryTags());
            }
        }
        Map<String, List<TimeRange>> hours = new TreeMap<>();
        store.getOpeningHours().forEach((day, ranges) -> hours.put(day.name(), List.copyOf(ranges)));
        return new StoreListingView(store.getId(), store.getSlug(), brand.getId(), brand.getName(), store.getBranchName(), brand.getBranchCount(),
                Map.copyOf(brand.getDescription()), List.copyOf(brand.getCuisines()), Map.copyOf(brand.getLogoUrls()),
                Map.copyOf(brand.getCoverUrls()), StoreService.location(store),
                (store.getAddress() != null) ? store.getAddress().area() : null,
                (store.getAddress() != null) ? store.getAddress().city() : null, store.getDeliveryMode(),
                store.isPickupEnabled(), store.getStatus(), store.isTemporarilyClosed(), store.getPrepMinutes(),
                StoreService.zone(locale).getId(), hours,
                store.getClosures().stream().flatMap((c) -> c.from().datesUntil(c.to().plusDays(1))).limit(400)
                    .map(Object::toString).toList(),
                dishNames, List.copyOf(dietary), StoreService.version(store.getVersion()));
    }

    public StoreIdPage storeIds(String afterId, int limit) {
        int size = Math.min(Math.max(limit, 1), 500);
        List<String> ids = this.repository.findByIdGreaterThanOrderByIdAsc((afterId != null) ? afterId : "", Limit.of(size))
            .stream()
            .map(StoreDocument::getId)
            .toList();
        return new StoreIdPage(ids, (ids.size() == size) ? ids.getLast() : null);
    }
}
