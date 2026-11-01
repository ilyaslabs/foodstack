package io.github.ilyaslabs.foodstack.cart.core.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument.CartLine;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartDocument;
import io.github.ilyaslabs.foodstack.cart.core.persistence.CartRepository;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.store.api.OrderLineRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationRequest;
import io.github.ilyaslabs.foodstack.store.api.OrderValidationView;
import io.github.ilyaslabs.foodstack.store.api.StoreApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * One cart per customer with lines from one branch (PRD CO-1). Every change re-checks the line
 * against the branch's menu, so the cart never holds something that cannot be ordered at the time
 * it was added. Prices are never stored: they are read from the store module when the cart is shown.
 */
public class CartService {

    static final int MAX_LINES = 100;

    static final int MAX_QUANTITY = 50;

    static final int MAX_NOTE = 200;

    private static final int WRITE_ATTEMPTS = 3;

    private final CartRepository carts;

    private final MongoTemplate mongo;

    private final StoreApi store;

    private final Clock clock;

    public CartService(CartRepository carts, MongoTemplate mongo, StoreApi store, Clock clock) {
        this.carts = carts;
        this.mongo = mongo;
        this.store = store;
        this.clock = clock;
    }

    public CartDetails get(String customerId) {
        CartDocument cart = this.carts.findById(customerId).orElse(null);
        if (cart == null || cart.lines() == null || cart.lines().isEmpty()) {
            return new CartDetails(customerId, (cart != null) ? cart.versionOrZero() : 0, null, null, List.of(), 0, 0, List.of());
        }
        OrderValidationView view = price(cart);
        List<CheckoutView.Line> lines = lines(cart, view);
        int count = cart.lines().stream().mapToInt(CartLine::quantity).sum();
        return new CartDetails(customerId, cart.versionOrZero(), view.store(), view.currency(), lines, view.subtotalMinor(),
                count, lineProblems(cart, view));
    }

    public CartDetails addLine(String customerId, AddLine add) {
        String note = cleanNote(add.note());
        List<String> options = List.copyOf(Objects.requireNonNullElse(add.optionIds(), List.of()));
        if (add.quantity() < 1 || add.quantity() > MAX_QUANTITY) {
            throw invalid("quantity", "must be between 1 and " + MAX_QUANTITY);
        }
        OrderValidationView check = this.store.validateOrder(new OrderValidationRequest(add.storeId(),
                List.of(new OrderLineRequest(add.itemId(), add.quantity(), options))));
        if (!check.store().live()) {
            throw new ModuleException.BadRequest("This restaurant is not taking orders", "STORE_NOT_LIVE");
        }
        if (!check.problems().isEmpty()) {
            throw new ModuleException.BadRequest("This item cannot be added as chosen", "LINE_INVALID",
                    Map.of("problems", check.problems().stream().map(CartService::problem).toList()));
        }
        write(customerId, (cart) -> {
            List<CartLine> lines = new ArrayList<>((cart != null && cart.lines() != null) ? cart.lines() : List.of());
            if (cart != null && cart.storeId() != null && !cart.storeId().equals(add.storeId()) && !lines.isEmpty()) {
                if (!add.replaceOtherStore()) {
                    throw new ModuleException.Conflict("Your cart has items from another restaurant", "CART_OTHER_STORE",
                            Map.of("storeId", cart.storeId()));
                }
                lines.clear();
            }
            int same = indexOfSame(lines, add.itemId(), options, note);
            if (same >= 0) {
                CartLine existing = lines.get(same);
                int quantity = existing.quantity() + add.quantity();
                if (quantity > MAX_QUANTITY) {
                    throw invalid("quantity", "at most " + MAX_QUANTITY + " of one item");
                }
                lines.set(same, new CartLine(existing.lineId(), existing.itemId(), quantity, existing.optionIds(), note));
            }
            else {
                if (lines.size() >= MAX_LINES) {
                    throw new ModuleException.BadRequest("A cart holds at most " + MAX_LINES + " lines", "CART_FULL");
                }
                lines.add(new CartLine(Ids.newId(), add.itemId(), add.quantity(), options, note));
            }
            return new CartDocument(customerId, add.storeId(), List.copyOf(lines), this.clock.instant(),
                    (cart != null) ? cart.version() : null);
        });
        return get(customerId);
    }

    /** Sets a line's quantity; 0 removes the line. */
    public CartDetails setQuantity(String customerId, String lineId, int quantity) {
        if (quantity < 0 || quantity > MAX_QUANTITY) {
            throw invalid("quantity", "must be between 0 and " + MAX_QUANTITY);
        }
        write(customerId, (cart) -> {
            List<CartLine> lines = new ArrayList<>((cart != null && cart.lines() != null) ? cart.lines() : List.of());
            int index = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).lineId().equals(lineId)) {
                    index = i;
                }
            }
            if (index < 0) {
                throw new ModuleException.NotFound("This line is no longer in your cart", "CART_LINE_NOT_FOUND");
            }
            CartLine line = lines.get(index);
            if (quantity == 0) {
                lines.remove(index);
            }
            else {
                lines.set(index, new CartLine(line.lineId(), line.itemId(), quantity, line.optionIds(), line.note()));
            }
            return new CartDocument(customerId, lines.isEmpty() ? null : cart.storeId(), List.copyOf(lines),
                    this.clock.instant(), cart.version());
        });
        return get(customerId);
    }

    public void clear(String customerId) {
        this.carts.deleteById(customerId);
    }

    /** Empties the cart only while it is still at {@code version} (after an order was placed from it). */
    public void clearIfVersion(String customerId, long version) {
        Criteria criteria = Criteria.where("_id").is(customerId);
        criteria = (version == 0) ? criteria.and("version").in(0L, null) : criteria.and("version").is(version);
        this.mongo.remove(Query.query(criteria), CartDocument.class);
    }

    /** The current cart document, or {@code null}. */
    public CartDocument find(String customerId) {
        return this.carts.findById(customerId).orElse(null);
    }

    /** Prices the cart's lines through the store module. */
    OrderValidationView price(CartDocument cart) {
        List<OrderLineRequest> lines = cart.lines()
            .stream()
            .map((line) -> new OrderLineRequest(line.itemId(), line.quantity(), line.optionIds()))
            .toList();
        return this.store.validateOrder(new OrderValidationRequest(cart.storeId(), lines));
    }

    static List<CheckoutView.Line> lines(CartDocument cart, OrderValidationView view) {
        List<CheckoutView.Line> lines = new ArrayList<>();
        for (int i = 0; i < cart.lines().size(); i++) {
            CartLine line = cart.lines().get(i);
            lines.add(new CheckoutView.Line(line.lineId(), line.note(), view.lines().get(i)));
        }
        return List.copyOf(lines);
    }

    static List<CheckoutView.Problem> lineProblems(CartDocument cart, OrderValidationView view) {
        return view.problems().stream().map((p) -> {
            Map<String, Object> details = new LinkedHashMap<>(problem(p));
            details.put("lineId", cart.lines().get(p.lineIndex()).lineId());
            return new CheckoutView.Problem(CheckoutView.LINE_PROBLEM, Map.copyOf(details));
        }).toList();
    }

    private static Map<String, Object> problem(OrderValidationView.LineProblem p) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lineIndex", p.lineIndex());
        details.put("itemId", p.itemId());
        details.put("reason", p.code());
        if (p.detail() != null) {
            details.put("detail", p.detail());
        }
        return details;
    }

    private void write(String customerId, UnaryOperator<CartDocument> change) {
        for (int attempt = 1;; attempt++) {
            CartDocument current = this.carts.findById(customerId).orElse(null);
            CartDocument changed = change.apply(current);
            try {
                if (changed.lines().isEmpty()) {
                    if (current != null) {
                        this.carts.delete(current);
                    }
                }
                else {
                    this.carts.save(changed);
                }
                return;
            }
            catch (OptimisticLockingFailureException | DuplicateKeyException ex) {
                if (attempt >= WRITE_ATTEMPTS) {
                    throw new ModuleException.Conflict("Your cart changed at the same time; try again", "CART_CHANGED");
                }
            }
        }
    }

    private static int indexOfSame(List<CartLine> lines, String itemId, List<String> options, String note) {
        for (int i = 0; i < lines.size(); i++) {
            CartLine line = lines.get(i);
            if (line.itemId().equals(itemId) && new java.util.HashSet<>(line.optionIds()).equals(new java.util.HashSet<>(options))
                    && Objects.equals(line.note(), note)) {
                return i;
            }
        }
        return -1;
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.strip();
        if (trimmed.length() > MAX_NOTE) {
            throw invalid("note", "must be at most " + MAX_NOTE + " characters");
        }
        return trimmed;
    }

    private static ModuleException invalid(String field, String message) {
        return new ModuleException.BadRequest("The cart line is invalid", "CART_INVALID",
                Map.of("errors", Map.of(field, message)));
    }
}
