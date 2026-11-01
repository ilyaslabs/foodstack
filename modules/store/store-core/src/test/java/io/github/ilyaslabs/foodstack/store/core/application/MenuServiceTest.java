package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MenuServiceTest {

    private static final LocaleView LOCALE = new LocaleView("en", List.of("en", "ar", "ur"), "PKR", "Asia/Karachi", 1);

    private final MenuSectionRepository sections = mock(MenuSectionRepository.class);

    private final MenuItemRepository items = mock(MenuItemRepository.class);

    private final ItemOverrideRepository overrides = mock(ItemOverrideRepository.class);

    private final StoreRepository stores = mock(StoreRepository.class);

    private final BrandRepository brands = mock(BrandRepository.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final MediaApi media = mock(MediaApi.class);

    private final MenuCache cache = mock(MenuCache.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final MenuService service = new MenuService(this.sections, this.items, this.overrides, this.stores, this.brands,
            this.settings, this.media, this.cache, this.outbox, this.transaction,
            Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.settings.getLocale()).willReturn(LOCALE);
        given(this.transaction.execute(any())).willAnswer((invocation) -> ((TransactionCallback<Object>) invocation
            .getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        org.mockito.Mockito.doAnswer((invocation) -> {
            ((java.util.function.Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(this.transaction).executeWithoutResult(any());
        given(this.brands.existsById("b1")).willReturn(true);
        given(this.stores.findByBrandId("b1")).willReturn(List.of(store("s1"), store("s2")));
        given(this.stores.findById("s1")).willReturn(Optional.of(store("s1")));
        given(this.sections.findById("sec1")).willReturn(Optional.of(section("sec1", "b1", 0)));
        given(this.items.insert(any(MenuItemDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));
        given(this.items.save(any(MenuItemDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));
        given(this.sections.insert(any(MenuSectionDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));
        given(this.cache.get(anyString(), any())).willAnswer((invocation) -> ((Supplier<?>) invocation.getArgument(1)).get());
    }

    @Test
    @DisplayName("adds an item with translations, claims its image, generates modifier ids and evicts every branch's menu")
    void createItem() {
        // given
        given(this.media.claim("m1", MediaPurpose.MENU_ITEM, new MediaOwner("store", "item-x")))
            .willReturn(image("m1"));
        given(this.media.claim(any(), any(), any())).willReturn(image("m1"));

        // when
        MenuDetails.Item item = this.service.createItem("b1", new MenuItemInput("sec1",
                Map.of("en", " Chicken Biryani ", "ar", "برياني دجاج", "ur", ""), null, 85_000, "m1",
                List.of("Halal", "spicy", "halal"),
                List.of(new ModifierGroup(null, Map.of("en", "Size"), 1, 1,
                        List.of(new ModifierOption(null, Map.of("en", "Half"), 0),
                                new ModifierOption(null, Map.of("en", "Full"), 40_000)))),
                null, 0), "owner-1");

        // then
        assertThat(item.name()).containsExactlyInAnyOrderEntriesOf(Map.of("en", "Chicken Biryani", "ar", "برياني دجاج"));
        assertThat(item.dietaryTags()).containsExactly("halal", "spicy");
        assertThat(item.imageUrls()).containsEntry("thumb", "https://cdn.example/m1/thumb.webp");
        assertThat(item.modifierGroups().getFirst().id()).isNotBlank();
        assertThat(item.modifierGroups().getFirst().options()).allSatisfy((o) -> assertThat(o.id()).isNotBlank());
        assertThat(item.branchIds()).isNull();
        verify(this.outbox).append(new MenuChanged("b1", "owner-1"));
        verify(this.cache).evict(List.of("s1", "s2"));
    }

    @Test
    @DisplayName("rejects an item without a default-language name, with unknown tags, bad modifiers or another brand's branch")
    void createItemInvalid() {
        assertThatThrownBy(() -> this.service.createItem("b1", new MenuItemInput("sec1", Map.of("ar", "برياني"), null, -1,
                null, List.of("organic"), List.of(new ModifierGroup(null, Map.of("en", "Size"), 2, 1, List.of())),
                Set.of("other-branch"), 0), "owner-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("MENU_INVALID"));
        verify(this.items, never()).insert(any(MenuItemDocument.class));
    }

    @Test
    @DisplayName("updates an item only with the version the editor saw and releases a replaced image")
    void updateItem() {
        // given
        MenuItemDocument existing = item("i1", "sec1", null);
        existing.setImageMediaId("old");
        existing.setVersion(2L);
        given(this.items.findById("i1")).willReturn(Optional.of(existing));
        MenuItemInput input = new MenuItemInput("sec1", Map.of("en", "Biryani"), null, 90_000, null, null, null, null, 0);

        // then
        assertThatThrownBy(() -> this.service.updateItem("b1", "i1", input, 1, "owner-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("MENU_VERSION_CONFLICT"));

        // when
        MenuDetails.Item updated = this.service.updateItem("b1", "i1", input, 2, "owner-1");

        // then
        assertThat(updated.priceMinor()).isEqualTo(90_000);
        assertThat(updated.imageMediaId()).isNull();
        verify(this.media).release("old", new MediaOwner("store", "item-i1"));
    }

    @Test
    @DisplayName("refuses to delete a section that still has items")
    void deleteSection() {
        given(this.items.countByBrandIdAndSectionIdAndDeletedAtIsNull("b1", "sec1")).willReturn(2L);
        assertThatThrownBy(() -> this.service.deleteSection("b1", "sec1", "owner-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("SECTION_NOT_EMPTY"));
    }

    @Test
    @DisplayName("marks an item sold out at one branch, publishes ItemAvailabilityChanged and evicts that branch only")
    void soldOut() {
        given(this.items.findById("i1")).willReturn(Optional.of(item("i1", "sec1", null)));
        given(this.overrides.findById("s1:i1")).willReturn(Optional.empty());

        this.service.setSoldOut("s1", "i1", null, true, "owner-1");

        verify(this.overrides).save(org.mockito.ArgumentMatchers.argThat((ItemOverrideDocument o) -> o.getId().equals("s1:i1")
                && o.getStoreId().equals("s1") && o.isSoldOut()));
        verify(this.outbox).append(new ItemAvailabilityChanged("s1", "i1", null, true, "owner-1"));
        verify(this.cache).evict(List.of("s1"));
        assertThatThrownBy(() -> this.service.setSoldOut("s1", "i1", "nope", true, "owner-1"))
            .isInstanceOfSatisfying(ModuleException.NotFound.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("MENU_OPTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("builds a branch's menu: only its items, sold-out marks merged, empty sections left out")
    void effectiveMenu() {
        // given
        given(this.sections.findByBrandIdOrderBySortOrderAsc("b1"))
            .willReturn(List.of(section("sec1", "b1", 0), section("sec2", "b1", 1), section("sec3", "b1", 2)));
        MenuItemDocument everywhere = item("i1", "sec1", null);
        MenuItemDocument otherBranch = item("i2", "sec2", Set.of("s2"));
        MenuItemDocument here = item("i3", "sec3", Set.of("s1"));
        given(this.items.findByBrandIdAndDeletedAtIsNullOrderBySortOrderAsc("b1"))
            .willReturn(List.of(everywhere, otherBranch, here));
        ItemOverrideDocument soldOut = new ItemOverrideDocument();
        soldOut.setId("s1:i1:full");
        soldOut.setSoldOut(true);
        ItemOverrideDocument itemSoldOut = new ItemOverrideDocument();
        itemSoldOut.setId("s1:i3");
        itemSoldOut.setSoldOut(true);
        given(this.overrides.findByStoreId("s1")).willReturn(List.of(soldOut, itemSoldOut));

        // when
        EffectiveMenu menu = this.service.effectiveMenu("s1");

        // then
        assertThat(menu.currency()).isEqualTo("PKR");
        assertThat(menu.sections()).extracting(EffectiveMenu.Section::id).containsExactly("sec1", "sec3");
        EffectiveMenu.Item first = menu.sections().getFirst().items().getFirst();
        assertThat(first.soldOut()).isFalse();
        assertThat(first.modifierGroups().getFirst().options()).extracting(EffectiveMenu.Option::soldOut).containsExactly(false, true);
        assertThat(menu.sections().get(1).items().getFirst().soldOut()).isTrue();
    }

    private static MediaAssetView image(String id) {
        return new MediaAssetView(id, MediaPurpose.MENU_ITEM, MediaStatus.READY,
                Map.of("thumb", new MediaVariantView("https://cdn.example/" + id + "/thumb.webp", 200, 200, 1000)), null, 0);
    }

    private static StoreDocument store(String id) {
        StoreDocument store = new StoreDocument();
        store.setId(id);
        store.setBrandId("b1");
        return store;
    }

    private static MenuSectionDocument section(String id, String brandId, int sortOrder) {
        MenuSectionDocument section = new MenuSectionDocument();
        section.setId(id);
        section.setBrandId(brandId);
        section.setName(Map.of("en", "Section " + id));
        section.setSortOrder(sortOrder);
        return section;
    }

    private static MenuItemDocument item(String id, String sectionId, Set<String> branchIds) {
        MenuItemDocument item = new MenuItemDocument();
        item.setId(id);
        item.setBrandId("b1");
        item.setSectionId(sectionId);
        item.setName(new java.util.LinkedHashMap<>(Map.of("en", "Item " + id)));
        item.setPriceMinor(50_000);
        item.setBranchIds(branchIds);
        item.setModifierGroups(List.of(new ModifierGroup("size", Map.of("en", "Size"), 1, 1,
                List.of(new ModifierOption("half", Map.of("en", "Half"), 0),
                        new ModifierOption("full", Map.of("en", "Full"), 40_000)))));
        return item;
    }
}
