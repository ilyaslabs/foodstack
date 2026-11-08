package io.github.ilyaslabs.foodstack.it.scenarios;

import io.github.ilyaslabs.foodstack.it.support.Topology;

import org.junit.jupiter.api.Nested;

/**
 * Every journey, one after another, on one topology. {@link MonolithLane} and
 * {@link MicroservicesLane} run side by side (junit-platform.properties): the two platforms use their
 * own databases, Redis and Kafka namespaces and buckets, so they never see each other's data, while
 * the journeys of one platform stay one at a time, because some change settings the others read.
 * A new journey is an abstract {@code *Scenario} class plus one line here; {@code LanesTest} fails
 * when that line is missing.
 */
abstract class Lane {

    abstract Topology topology();

    @Nested
    class PlatformSetup extends PlatformSetupScenario {
        PlatformSetup() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class AccountRecovery extends AccountRecoveryScenario {
        AccountRecovery() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class TwoStepAndSocial extends TwoStepAndSocialScenario {
        TwoStepAndSocial() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class Catalogue extends CatalogueScenario {
        Catalogue() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class Ordering extends OrderingScenario {
        Ordering() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class Delivery extends DeliveryScenario {
        Delivery() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class MoneyAndTrust extends MoneyAndTrustScenario {
        MoneyAndTrust() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class CouponsAndRefunds extends CouponsAndRefundsScenario {
        CouponsAndRefunds() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class PricesAndSettlement extends PricesAndSettlementScenario {
        PricesAndSettlement() {
            super(Lane.this.topology());
        }
    }

    @Nested
    class DeadLetter extends DeadLetterScenario {
        DeadLetter() {
            super(Lane.this.topology());
        }
    }
}
