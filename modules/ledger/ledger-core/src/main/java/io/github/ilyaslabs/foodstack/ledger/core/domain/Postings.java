package io.github.ilyaslabs.foodstack.ledger.core.domain;

import java.util.ArrayList;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.order.api.OrderMoney;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * The double-entry rules (PRD FI-1, FI-7, DP-5; TDD 5.4, 12): which accounts each money event
 * moves, and by how much. Every result balances to zero. Pure functions, so every rule is tested
 * on its own.
 */
public final class Postings {

    private Postings() {
    }

    /**
     * The commission on an order: a percentage of the food after the discounts the store funds
     * (ilyas, 2026-11-06), plus the fixed amount per order.
     */
    public static long commission(OrderMoney m) {
        long base = Math.max(0, m.subtotalMinor() - storeItemsDiscount(m));
        return percent(base, m.commissionBasisPoints()) + Math.max(0, m.commissionFixedMinor());
    }

    /** The part of the food discount the store funds; the operator's share is split over food and delivery pro rata. */
    static long storeItemsDiscount(OrderMoney m) {
        long discount = m.itemsDiscountMinor() + m.deliveryDiscountMinor();
        if (discount <= 0) {
            return 0;
        }
        long operator = Math.min(Math.max(0, m.operatorDiscountMinor()), discount);
        long operatorOnItems = Math.round((double) operator * m.itemsDiscountMinor() / discount);
        return m.itemsDiscountMinor() - operatorOnItems;
    }

    /** Hundredths of a percent of an amount, rounded half up. */
    static long percent(long amountMinor, int basisPoints) {
        if (amountMinor <= 0 || basisPoints <= 0) {
            return 0;
        }
        return Math.floorDiv(amountMinor * basisPoints + 5_000, 10_000);
    }

    /**
     * A delivered or collected order. What the customer paid comes out of where the money is (the
     * money paid for orders, cash a branch or a rider holds, or the branch's own merchant account)
     * and is shared out: food, tax and its own delivery charge to the branch, less the discounts it
     * funds and the commission; the commission, the operator's delivery charge and the service fee to
     * the operator, less the discounts it funds; the tip to the branch (its own rider, or pickup) or,
     * for operator deliveries, to the rider pool until dispatch says which rider earned it.
     */
    public static List<Leg> orderCompleted(String storeId, PaymentMethod method, OrderMoney m) {
        String store = Accounts.store(storeId);
        DeliveryMode mode = m.deliveryMode();
        boolean operatorDelivers = mode == DeliveryMode.OPERATOR;
        long discount = m.itemsDiscountMinor() + m.deliveryDiscountMinor();
        long operatorFunds = Math.min(Math.max(0, m.operatorDiscountMinor()), discount);
        long storeFunds = discount - operatorFunds;
        long commission = commission(m);
        long total = m.totalMinor();

        Builder legs = new Builder();
        if (OrderMoney.STORE.equals(m.collectedBy())) {
            legs.add(store, Kinds.COLLECTED, -total);
        }
        else if (method == PaymentMethod.CASH) {
            legs.add(operatorDelivers ? Accounts.CASH : store, operatorDelivers ? Kinds.PAYMENT : Kinds.CASH, -total);
        }
        else {
            legs.add(Accounts.PREPAID, Kinds.PAYMENT, -total);
        }
        legs.add(store, Kinds.FOOD, m.subtotalMinor());
        legs.add(store, Kinds.TAX, m.taxMinor());
        legs.add(store, Kinds.DISCOUNT, -storeFunds);
        legs.add(store, Kinds.COMMISSION, -commission);
        legs.add(Accounts.COMMISSION, Kinds.COMMISSION, commission);
        legs.add(operatorDelivers ? Accounts.DELIVERY : store, Kinds.DELIVERY, m.deliveryChargeMinor());
        legs.add(Accounts.SERVICE_FEE, Kinds.SERVICE_FEE, m.serviceFeeMinor());
        legs.add(Accounts.DISCOUNTS, Kinds.DISCOUNT, -operatorFunds);
        legs.add(operatorDelivers ? Accounts.RIDER_POOL : store, Kinds.TIP, m.tipMinor());
        return legs.balanced();
    }

    /**
     * An operator rider finished a delivery: their pay is the operator's cost, the tip leaves the
     * rider pool for them, and the cash they took moves onto their account, so a rider's balance is
     * pay plus tips less cash carried. A branch's own rider is paid by the branch; nothing is posted.
     */
    public static List<Leg> deliveryCompleted(String riderId, String riderKind, long earningMinor, long tipMinor,
            long cashMinor) {
        if (riderId == null || !"OPERATOR".equals(riderKind)) {
            return List.of();
        }
        String rider = Accounts.rider(riderId);
        Builder legs = new Builder();
        legs.add(rider, Kinds.EARNING, earningMinor);
        legs.add(Accounts.RIDER_PAY, Kinds.EARNING, -earningMinor);
        legs.add(rider, Kinds.TIP, tipMinor);
        legs.add(Accounts.RIDER_POOL, Kinds.TIP, -tipMinor);
        legs.add(rider, Kinds.CASH, -cashMinor);
        legs.add(Accounts.CASH, Kinds.PAYMENT, cashMinor);
        return legs.balanced();
    }

    /**
     * Support gave money back on a done order and picked who bears it (ilyas, 2026-11-06): the
     * branch (default) or the operator. The money leaves the operator's card money, or, for direct
     * collection, the branch's own account (the branch is owed it back when the operator bears it).
     */
    public static List<Leg> refundFunded(String storeId, long amountMinor, String fundedBy, String collectedBy) {
        String store = Accounts.store(storeId);
        Builder legs = new Builder();
        legs.add("OPERATOR".equals(fundedBy) ? Accounts.REFUNDS : store, Kinds.REFUND, -amountMinor);
        if (OrderMoney.STORE.equals(collectedBy)) {
            legs.add(store, Kinds.COLLECTED, amountMinor);
        }
        else {
            legs.add(Accounts.PREPAID, Kinds.PAYMENT, amountMinor);
        }
        return legs.balanced();
    }

    /** Card money reached the operator's gateway for an order. */
    public static List<Leg> paymentReceived(long amountMinor) {
        Builder legs = new Builder();
        legs.add(Accounts.GATEWAY, Kinds.PAYMENT, -amountMinor);
        legs.add(Accounts.PREPAID, Kinds.PAYMENT, amountMinor);
        return legs.balanced();
    }

    /** Card money went back to the customer from the operator's gateway. */
    public static List<Leg> refundPaidOut(long amountMinor) {
        Builder legs = new Builder();
        legs.add(Accounts.GATEWAY, Kinds.PAYMENT, amountMinor);
        legs.add(Accounts.PREPAID, Kinds.PAYMENT, -amountMinor);
        return legs.balanced();
    }

    /**
     * Finance settled a party's balance: what was due moves to the operator's bank account, leaving
     * the party at zero for the period. Positive: the operator paid the party.
     */
    public static List<Leg> settlement(String accountId, long dueMinor) {
        Builder legs = new Builder();
        legs.add(accountId, Kinds.SETTLEMENT, -dueMinor);
        legs.add(Accounts.BANK, Kinds.SETTLEMENT, dueMinor);
        return legs.balanced();
    }

    /** Collects legs, skipping zero amounts, and checks they balance. */
    static final class Builder {

        private final List<Leg> legs = new ArrayList<>();

        void add(String accountId, String kind, long amountMinor) {
            if (amountMinor != 0) {
                this.legs.add(new Leg(accountId, kind, amountMinor));
            }
        }

        List<Leg> balanced() {
            long sum = this.legs.stream().mapToLong(Leg::amountMinor).sum();
            if (sum != 0) {
                throw new IllegalStateException("Ledger transaction does not balance by " + sum + ": " + this.legs);
            }
            return List.copyOf(this.legs);
        }
    }
}
