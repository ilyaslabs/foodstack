package io.github.ilyaslabs.foodstack.it.scenarios;

import io.github.ilyaslabs.foodstack.it.support.Topology;

/** Every journey with all modules in one process, as Docker Compose runs them. */
class MonolithLane extends Lane {

    @Override
    Topology topology() {
        return Topology.MONOLITH;
    }
}
