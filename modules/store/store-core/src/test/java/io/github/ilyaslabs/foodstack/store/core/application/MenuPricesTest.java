package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.OrderingView;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MenuPricesTest {

    private final MenuItemRepository items = mock(MenuItemRepository.class);

    private final ItemOverrideRepository overrides = mock(ItemOverrideRepository.class);

    private final PriceChangeRepository changes = mock(PriceChangeRepository.class);

    private final StoreRepository stores = mock(StoreRepository.class);

    private final BrandRepository brands = mock(BrandRepository.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final MenuCache cache = mock(MenuCache.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final MenuPrices prices = new MenuPrices(this.items, this.overrides, this.changes, this.stores, this.brands,
            this.settings, this.cache, this.outbox, this.transaction,
            Clock.fixed(Instant.parse("2026-11-06T09:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        doAnswer((invocation) -> {
            ((java.util.function.Consumer<TransactionStatus>) invocation.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(this.transaction).executeWithoutResult(any());
        StoreDocument store = new StoreDocument();
        store.setId("s1");
        store.setBrandId("b1");
        given(this.stores.findById("s1")).willReturn(Optional.of(store));
        given(this.stores.findByBrandId("b1")).willReturn(List.of(store));
        given(this.items.findById("i1")).willReturn(Optional.of(item()));
        given(this.overrides.findById(any())).willReturn(Optional.empty());
        given(this.changes.findById(any())).willReturn(Optional.empty());
        given(this.changes.save(any(PriceChangeDocument.class))).willAnswer((invocation) -> invocation.getArgument(0));
        approval(false);
        signIn("store:portal");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a branch price applies at once when approval is off, and the menu is rebuilt")
    void branchPriceApplied() {
        assertThat(this.prices.setBranchPrice("s1", "i1", null, 55_000L, "owner-1")).isEqualTo(MenuPrices.Outcome.APPLIED);

        ArgumentCaptor<ItemOverrideDocument> saved = ArgumentCaptor.forClass(ItemOverrideDocument.class);
        verify(this.overrides).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("s1:i1");
        assertThat(saved.getValue().getPriceOverrideMinor()).isEqualTo(55_000L);
        verify(this.outbox).append(new MenuChanged("b1", "owner-1"));
        verify(this.cache).evict(List.of("s1"));
    }

    @Test
    @DisplayName("an option price on a branch is kept per option; the brand price itself clears the override")
    void optionPrice() {
        this.prices.setBranchPrice("s1", "i1", "full", 45_000L, "owner-1");
        this.prices.setBranchPrice("s1", "i1", null, 50_000L, "owner-1");

        ArgumentCaptor<ItemOverrideDocument> saved = ArgumentCaptor.forClass(ItemOverrideDocument.class);
        verify(this.overrides, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getId()).isEqualTo("s1:i1:full");
        assertThat(saved.getAllValues().get(0).getPriceOverrideMinor()).isEqualTo(45_000L);
        assertThat(saved.getAllValues().get(1).getPriceOverrideMinor()).isNull();
        assertThatThrownBy(() -> this.prices.setBranchPrice("s1", "i1", "huge", 1L, "owner-1"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("MENU_OPTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("with approval on, a store owner's branch price waits and the menu keeps the old price")
    void branchPricePending() {
        approval(true);

        assertThat(this.prices.setBranchPrice("s1", "i1", null, 55_000L, "owner-1")).isEqualTo(MenuPrices.Outcome.PENDING);

        ArgumentCaptor<PriceChangeDocument> request = ArgumentCaptor.forClass(PriceChangeDocument.class);
        verify(this.changes).save(request.capture());
        assertThat(request.getValue().id()).isEqualTo("b1:s1:i1");
        assertThat(request.getValue().currentMinor()).isEqualTo(50_000);
        assertThat(request.getValue().proposedMinor()).isEqualTo(55_000L);
        assertThat(request.getValue().status()).isEqualTo(PriceChangeDocument.PENDING);
        verify(this.overrides, never()).save(any());
    }

    @Test
    @DisplayName("operator staff change prices without approval")
    void operatorSkipsApproval() {
        approval(true);
        signIn("store:manage");

        assertThat(this.prices.setBranchPrice("s1", "i1", null, 55_000L, "admin-1")).isEqualTo(MenuPrices.Outcome.APPLIED);
    }

    @Test
    @DisplayName("an item edit keeps the old item and option prices and asks for each change; other edits apply")
    void holdBrandPrices() {
        approval(true);
        MenuItemInput edit = new MenuItemInput("sec1", Map.of("en", "Renamed"), Map.of(), 60_000, null, List.of(),
                List.of(new ModifierGroup("size", Map.of("en", "Size"), 1, 1,
                        List.of(new ModifierOption("half", Map.of("en", "Half"), 0),
                                new ModifierOption("full", Map.of("en", "Full"), 45_000),
                                new ModifierOption(null, Map.of("en", "Family"), 90_000)))),
                null, 0);

        MenuPrices.Held held = this.prices.hold(item(), edit, "brand-1");

        assertThat(held.input().priceMinor()).isEqualTo(50_000);
        assertThat(held.input().name()).containsEntry("en", "Renamed");
        assertThat(held.input().modifierGroups().getFirst().options())
            .extracting(ModifierOption::priceDeltaMinor)
            .containsExactly(0L, 40_000L, 90_000L);
        assertThat(held.requests()).extracting(PriceChangeDocument::id).containsExactly("b1:brand:i1", "b1:brand:i1:full");
        assertThat(held.requests()).extracting(PriceChangeDocument::proposedMinor).containsExactly(60_000L, 45_000L);
    }

    @Test
    @DisplayName("approving a brand option price writes it to the menu item; only operator staff decide")
    void approve() {
        PriceChangeDocument request = new PriceChangeDocument("b1:brand:i1:full", "b1", null, "i1", "full", 40_000, 45_000L,
                PriceChangeDocument.PENDING, "brand-1", Instant.EPOCH, null, null, 0L);
        given(this.changes.findById("b1:brand:i1:full")).willReturn(Optional.of(request));
        given(this.items.findAllById(any())).willReturn(List.of(item()));
        given(this.brands.findAllById(any())).willReturn(List.of());
        given(this.stores.findAllById(any())).willReturn(List.of());

        assertThatThrownBy(() -> this.prices.decide("b1:brand:i1:full", true, "owner-1"))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_ACCESS_DENIED"));

        signIn("store:manage");
        MenuPrices.PriceChange decided = this.prices.decide("b1:brand:i1:full", true, "admin-1");

        assertThat(decided.status()).isEqualTo(PriceChangeDocument.APPROVED);
        ArgumentCaptor<MenuItemDocument> saved = ArgumentCaptor.forClass(MenuItemDocument.class);
        verify(this.items).save(saved.capture());
        assertThat(saved.getValue().getModifierGroups().getFirst().options())
            .extracting(ModifierOption::priceDeltaMinor)
            .containsExactly(0L, 45_000L);
        verify(this.cache).evict(List.of("s1"));
    }

    private void approval(boolean on) {
        given(this.settings.getOrdering()).willReturn(new OrderingView(true, false, 0, 0, 0, true, 180, 1, on));
    }

    private static void signIn(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", authority));
    }

    private static MenuItemDocument item() {
        MenuItemDocument item = new MenuItemDocument();
        item.setId("i1");
        item.setBrandId("b1");
        item.setSectionId("sec1");
        item.setName(new java.util.LinkedHashMap<>(Map.of("en", "Biryani")));
        item.setPriceMinor(50_000);
        item.setModifierGroups(List.of(new ModifierGroup("size", Map.of("en", "Size"), 1, 1,
                List.of(new ModifierOption("half", Map.of("en", "Half"), 0),
                        new ModifierOption("full", Map.of("en", "Full"), 40_000)))));
        return item;
    }
}
