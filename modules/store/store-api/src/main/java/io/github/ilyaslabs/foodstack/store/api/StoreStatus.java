package io.github.ilyaslabs.foodstack.store.api;

/** Whether customers can see a branch. */
public enum StoreStatus {

    /** Being set up after onboarding; invisible to customers. */
    DRAFT,

    /** Visible and ordering according to its hours. */
    LIVE,

    /** Hidden by the operator (for example unpaid commission or a quality issue). */
    PAUSED
}
