package io.github.ilyaslabs.foodstack.rider.api;

/** Whose rider (PRD DR-1). */
public enum RiderKind {

    /** The operator's fleet: gets offers for operator-delivery orders and is paid by the operator. */
    OPERATOR,

    /** A store's own rider: gets that store's jobs only and is paid by the store outside FoodStack. */
    STORE
}
