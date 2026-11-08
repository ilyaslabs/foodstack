package io.github.ilyaslabs.foodstack.it.scenarios;

import io.github.ilyaslabs.foodstack.it.support.Topology;

/** Every journey with one service per module, each calling the others over HTTP. */
class MicroservicesLane extends Lane {

    @Override
    Topology topology() {
        return Topology.MICROSERVICES;
    }
}
