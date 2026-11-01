package io.github.ilyaslabs.foodstack.cart.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument.CartLine;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartRepository;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView.LineProblem;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CartServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final CartRepository repository = mock(CartRepository.class);

    private final StoreApi store = mock(StoreApi.class);

    private final CartService service = new CartService(this.repository, mock(MongoTemplate.class), this.store,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(this.repository.findById("c1")).willReturn(Optional.empty());
        given(this.store.validateOrder(any())).willAnswer((invocation) -> {
            OrderValidationRequest request = invocation.getArgument(0);
            long[] totals = request.lines().stream().mapToLong((l) -> 85_000L * l.quantity()).toArray();
            return CartFixtures.priced(request.storeId(), totals);
        });
    }

    @Test
    @DisplayName("adds a checked line to a new cart and prices it through the store module")
    void addToNewCart() {
        this.service.addLine("c1", new AddLine("s1", "i1", 2, List.of("o1"), "  extra spicy ", false));

        CartDocument saved = saved();
        assertThat(saved.storeId()).isEqualTo("s1");
        assertThat(saved.lines()).singleElement().satisfies((line) -> {
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.note()).isEqualTo("extra spicy");
        });
        assertThat(saved.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("the same item with the same choices and note adds to the existing line")
    void mergesSameLine() {
        given(this.repository.findById("c1")).willReturn(Optional.of(new CartDocument("c1", "s1",
                List.of(new CartLine("l1", "i1", 1, List.of("o2", "o1"), null)), NOW, 3L)));

        this.service.addLine("c1", new AddLine("s1", "i1", 2, List.of("o1", "o2"), null, false));

        assertThat(saved().lines()).singleElement().satisfies((line) -> {
            assertThat(line.lineId()).isEqualTo("l1");
            assertThat(line.quantity()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("asks before mixing restaurants, and starts over when the customer agrees (PRD CO-1)")
    void otherStore() {
        given(this.repository.findById("c1")).willReturn(Optional.of(new CartDocument("c1", "s1",
                List.of(new CartLine("l1", "i1", 1, List.of(), null)), NOW, 3L)));

        assertThatThrownBy(() -> this.service.addLine("c1", new AddLine("s2", "i9", 1, List.of(), null, false)))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> {
                assertThat(ex.code()).isEqualTo("CART_OTHER_STORE");
                assertThat(ex.details()).containsEntry("storeId", "s1");
            });
        verify(this.repository, never()).save(any());

        this.service.addLine("c1", new AddLine("s2", "i9", 1, List.of(), null, true));
        assertThat(saved().storeId()).isEqualTo("s2");
        assertThat(saved().lines()).extracting(CartLine::itemId).containsExactly("i9");
    }

    @Test
    @DisplayName("refuses a line the store says cannot be ordered, a closed-for-business store and bad quantities")
    void refuses() {
        org.mockito.BDDMockito.willReturn(CartFixtures.priced(CartFixtures.store("s1", true, true, true),
                List.of(new LineProblem(0, "i1", OrderValidationView.ITEM_SOLD_OUT, null)), 0)).given(this.store).validateOrder(any());
        assertThatThrownBy(() -> this.service.addLine("c1", new AddLine("s1", "i1", 1, List.of(), null, false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("LINE_INVALID"));

        org.mockito.BDDMockito.willReturn(CartFixtures.priced(CartFixtures.store("s1", false, true, true),
                List.of(), 1)).given(this.store).validateOrder(any());
        assertThatThrownBy(() -> this.service.addLine("c1", new AddLine("s1", "i1", 1, List.of(), null, false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("STORE_NOT_LIVE"));

        assertThatThrownBy(() -> this.service.addLine("c1", new AddLine("s1", "i1", 51, List.of(), null, false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("CART_INVALID"));
        assertThatThrownBy(() -> this.service.addLine("c1", new AddLine("s1", "i1", 1, List.of(), "x".repeat(201), false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("CART_INVALID"));
    }

    @Test
    @DisplayName("changing a quantity to 0 removes the line, and the last line removes the cart")
    void quantities() {
        CartDocument cart = new CartDocument("c1", "s1", List.of(new CartLine("l1", "i1", 1, List.of(), null),
                new CartLine("l2", "i2", 1, List.of(), null)), NOW, 3L);
        given(this.repository.findById("c1")).willReturn(Optional.of(cart));

        this.service.setQuantity("c1", "l1", 4);
        assertThat(saved().lines()).extracting(CartLine::quantity).containsExactly(4, 1);

        given(this.repository.findById("c1")).willReturn(Optional.of(new CartDocument("c1", "s1",
                List.of(new CartLine("l2", "i2", 1, List.of(), null)), NOW, 4L)));
        this.service.setQuantity("c1", "l2", 0);
        verify(this.repository).delete(any(CartDocument.class));

        assertThatThrownBy(() -> this.service.setQuantity("c1", "gone", 1))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("CART_LINE_NOT_FOUND"));
    }

    @Test
    @DisplayName("retries a write that lost a race, then reports the cart changed")
    void retries() {
        given(this.repository.save(any())).willThrow(new OptimisticLockingFailureException("race"));

        assertThatThrownBy(() -> this.service.addLine("c1", new AddLine("s1", "i1", 1, List.of(), null, false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("CART_CHANGED"));
        verify(this.repository, times(3)).save(any());
    }

    @Test
    @DisplayName("shows line problems with the line id, so the app can point at the line")
    void getWithProblems() {
        given(this.repository.findById("c1")).willReturn(Optional.of(new CartDocument("c1", "s1",
                List.of(new CartLine("l1", "i1", 2, List.of(), null)), NOW, 3L)));
        org.mockito.BDDMockito.willReturn(CartFixtures.priced(CartFixtures.store("s1", true, true, true),
                List.of(new LineProblem(0, "i1", OrderValidationView.ITEM_SOLD_OUT, null)), 170_000)).given(this.store).validateOrder(any());

        CartDetails details = this.service.get("c1");

        assertThat(details.itemCount()).isEqualTo(2);
        assertThat(details.subtotalMinor()).isEqualTo(170_000);
        assertThat(details.problems()).singleElement().satisfies((p) -> {
            assertThat(p.code()).isEqualTo(CheckoutView.LINE_PROBLEM);
            assertThat(p.details()).containsEntry("lineId", "l1").containsEntry("reason", "ITEM_SOLD_OUT");
        });
        assertThat(this.service.get("nobody").lines()).isEmpty();
    }

    private CartDocument saved() {
        ArgumentCaptor<CartDocument> captor = ArgumentCaptor.forClass(CartDocument.class);
        verify(this.repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
