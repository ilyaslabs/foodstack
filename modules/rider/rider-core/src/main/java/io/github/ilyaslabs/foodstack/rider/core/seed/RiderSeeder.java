package io.github.ilyaslabs.foodstack.rider.core.seed;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderInput;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Demo riders for the {@code test-seed} profile (TDD 16): two operator riders, who take Spice
 * Route's deliveries, and one rider of Napoli Slice's own. They start offline; signing in to the
 * rider screen and going online puts them on the map. Runs after the store seeder, once.
 */
@Component
@Profile("test-seed")
class RiderSeeder {

    /** Same as the other demo users (README). */
    static final String PASSWORD = "foodstack-demo";

    /** Napoli Slice (DHA) in the store module's demo data. */
    static final String NAPOLI_DHA = "0195f0a0-0000-7000-8000-000000000201";

    private static final Log logger = LogFactory.getLog(RiderSeeder.class);

    private final RiderService riders;

    private final MongoTemplate mongo;

    private final ServiceCalls serviceCalls;

    RiderSeeder(RiderService riders, MongoTemplate riderMongoTemplate, ServiceCalls serviceCalls) {
        this.riders = riders;
        this.mongo = riderMongoTemplate;
        this.serviceCalls = serviceCalls;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(40)
    void seed() {
        try {
            this.mongo.insert(new Document("_id", "v1").append("seededAt", Instant.now()), "rider_seed");
        }
        catch (DuplicateKeyException ex) {
            return;
        }
        this.serviceCalls.run(() -> {
            this.riders.addOperatorRider(new RiderInput("rider@demo.foodstack.local", PASSWORD, "Bilal Ahmed", "+923001234501",
                    Vehicle.MOTORBIKE, "LEB-2741", "Lahore", null));
            this.riders.addOperatorRider(new RiderInput("rider2@demo.foodstack.local", PASSWORD, "Ayesha Noor", "+923001234502",
                    Vehicle.SCOOTER, "LEC-0915", "Lahore", null));
            this.riders.addStoreRider(NAPOLI_DHA, new RiderInput("napoli.rider@demo.foodstack.local", PASSWORD, "Usman Tariq",
                    "+923001234503", Vehicle.MOTORBIKE, "LED-3380", "Lahore", null));
        });
        logger.info("Seeded demo riders rider@demo.foodstack.local, rider2@demo.foodstack.local (operator) and "
                + "napoli.rider@demo.foodstack.local (Napoli Slice), password '" + PASSWORD + "'");
    }
}
