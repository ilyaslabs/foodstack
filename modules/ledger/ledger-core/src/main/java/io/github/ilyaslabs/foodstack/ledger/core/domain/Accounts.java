package io.github.ilyaslabs.foodstack.ledger.core.domain;

/**
 * The ledger's accounts (PRD FI-1, FI-7). A party's account balance is what the operator owes the
 * party: positive means the operator pays the branch or rider, negative means they pay the operator.
 * The operator's own accounts hold its earnings (positive) and costs (negative); the clearing
 * accounts hold money on its way (card money at the gateway, money paid for orders not yet done,
 * cash a rider is carrying, tips not yet given to a rider).
 */
public final class Accounts {

    public static final String STORE_PREFIX = "store:";

    public static final String RIDER_PREFIX = "rider:";

    public static final String OPERATOR_PREFIX = "operator:";

    public static final String COMMISSION = "operator:commission";

    public static final String DELIVERY = "operator:delivery";

    public static final String SERVICE_FEE = "operator:service-fee";

    public static final String DISCOUNTS = "operator:discounts";

    public static final String RIDER_PAY = "operator:rider-pay";

    public static final String REFUNDS = "operator:refunds";

    /** Money the operator paid out to, or received from, branches and riders when settling. */
    public static final String BANK = "operator:bank";

    /** Card money held at the operator's gateway. */
    public static final String GATEWAY = "clearing:gateway";

    /** Money customers paid for orders that are not done yet. */
    public static final String PREPAID = "clearing:prepaid";

    /** Cash for operator deliveries, until the rider who took it is known. */
    public static final String CASH = "clearing:cash";

    /** Tips on operator deliveries, until the rider who earned them is known. */
    public static final String RIDER_POOL = "clearing:rider-pool";

    public static final String STORE = "STORE";

    public static final String RIDER = "RIDER";

    public static final String OPERATOR = "OPERATOR";

    public static final String CLEARING = "CLEARING";

    private Accounts() {
    }

    public static String store(String storeId) {
        return STORE_PREFIX + storeId;
    }

    public static String rider(String riderId) {
        return RIDER_PREFIX + riderId;
    }

    /** {@code STORE}, {@code RIDER}, {@code OPERATOR} or {@code CLEARING}. */
    public static String partyType(String accountId) {
        if (accountId.startsWith(STORE_PREFIX)) {
            return STORE;
        }
        if (accountId.startsWith(RIDER_PREFIX)) {
            return RIDER;
        }
        return accountId.startsWith(OPERATOR_PREFIX) ? OPERATOR : CLEARING;
    }

    /** The branch or rider id of a party account, else the account id. */
    public static String partyId(String accountId) {
        int colon = accountId.indexOf(':');
        String type = partyType(accountId);
        return (STORE.equals(type) || RIDER.equals(type)) ? accountId.substring(colon + 1) : accountId;
    }
}
