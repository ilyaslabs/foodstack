package io.github.ilyaslabs.foodstack.rider.api;

/** Whether a rider may work. */
public enum RiderStatus {

    ACTIVE,

    /** Taken off the road by the operator or the store; cannot go online. */
    SUSPENDED
}
