package io.github.ilyaslabs.foodstack.identity.core.seed;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.application.NewUser;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
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
 * Demo users for the {@code test-seed} profile (TDD 16): an admin, finance and support staff and a
 * customer. Writes only identity's own data, once; logins are printed once in the startup log.
 */
@Component
@Profile("test-seed")
class IdentitySeeder {

    static final String PASSWORD = "foodstack-demo";

    private static final Log logger = LogFactory.getLog(IdentitySeeder.class);

    private final UserService users;

    private final MongoTemplate mongo;

    IdentitySeeder(UserService users, MongoTemplate identityMongoTemplate) {
        this.users = users;
        this.mongo = identityMongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    void seed() {
        try {
            this.mongo.insert(new Document("_id", "v1").append("seededAt", Instant.now()), "identity_seed");
        }
        catch (DuplicateKeyException ex) {
            return;
        }
        List<NewUser> demo = List.of(
                new NewUser("admin@demo.foodstack.local", PASSWORD, "Demo Admin", EnumSet.of(Role.ADMIN)),
                new NewUser("finance@demo.foodstack.local", PASSWORD, "Demo Finance", EnumSet.of(Role.FINANCE)),
                new NewUser("support@demo.foodstack.local", PASSWORD, "Demo Support", EnumSet.of(Role.SUPPORT)),
                new NewUser("customer@demo.foodstack.local", PASSWORD, "Demo Customer", EnumSet.of(Role.CUSTOMER)));
        StringBuilder logins = new StringBuilder("Seeded demo users (password '" + PASSWORD + "'):");
        for (NewUser user : demo) {
            if (!this.users.emailExists(user.email())) {
                this.users.create(user, "seed");
            }
            logins.append(System.lineSeparator()).append("  ").append(user.roles()).append(' ').append(user.email());
        }
        logger.info(logins);
    }
}
