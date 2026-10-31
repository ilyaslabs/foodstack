package io.github.ilyaslabs.foodstack.it.support;

/** How the platform is started for a scenario run (Testing Strategy 3.2). */
public enum Topology {

    /** One application context, every module local: the unit deployer's configuration. */
    MONOLITH,

    /** One application context per module, every other module called over HTTP. */
    MICROSERVICES
}
