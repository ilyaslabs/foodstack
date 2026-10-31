package io.github.ilyaslabs.foodstack.geo.api;

/** Who delivers a branch's orders (PRD ST-13). The branch owns the setting; geo prices it. */
public enum DeliveryMode {

    /** The branch's own riders, zones and charges (the default). */
    SELF,

    /** The operator's riders and delivery pricing. */
    OPERATOR
}
