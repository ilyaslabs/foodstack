package io.github.ilyaslabs.foodstack.ledger.core.domain;

import java.util.List;

/** What a ledger line is for; statements and payout reports group by it (PRD FI-1, FI-3). */
public final class Kinds {

    /** Food sold, before discounts. */
    public static final String FOOD = "FOOD";

    public static final String TAX = "TAX";

    public static final String DELIVERY = "DELIVERY";

    public static final String TIP = "TIP";

    /** A coupon discount, as funded by this account. */
    public static final String DISCOUNT = "DISCOUNT";

    public static final String COMMISSION = "COMMISSION";

    public static final String SERVICE_FEE = "SERVICE_FEE";

    /** A refund this account bears. */
    public static final String REFUND = "REFUND";

    /** Cash the party took from customers and holds for the operator. */
    public static final String CASH = "CASH";

    /** Card money that reached the branch's own merchant account (direct collection). */
    public static final String COLLECTED = "COLLECTED";

    /** Rider pay for deliveries. */
    public static final String EARNING = "EARNING";

    /** Money paid between the operator and a party to settle a balance. */
    public static final String SETTLEMENT = "SETTLEMENT";

    /** Customer money moving through the clearing accounts (card payments, refunds paid out, order totals). */
    public static final String PAYMENT = "PAYMENT";

    /** Columns of the payout reports, in order. */
    public static final List<String> REPORTED = List.of(FOOD, TAX, DELIVERY, TIP, DISCOUNT, COMMISSION, SERVICE_FEE, REFUND,
            CASH, COLLECTED, EARNING, SETTLEMENT);

    private Kinds() {
    }
}
