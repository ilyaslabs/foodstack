package io.github.ilyaslabs.foodstack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The FoodStack server. Every module is on the classpath; Module-Switch configuration decides
 * which run here ({@code local}), which are called on another deployment ({@code http}) and which
 * are {@code off}.
 * <p>
 * With {@code --foodstack.migrate-only=true} every module is local, each module's migrations run
 * against its own database, and the process exits (TDD 6.4).
 */
@SpringBootApplication
public class FoodstackApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(FoodstackApplication.class);
        ConfigurableApplicationContext context = application.run(args);
        if (MigrateOnly.isEnabled(context.getEnvironment())) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
