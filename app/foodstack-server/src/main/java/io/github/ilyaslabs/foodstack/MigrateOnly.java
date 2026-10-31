package io.github.ilyaslabs.foodstack;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

/**
 * {@code foodstack.migrate-only=true}: every module local whatever the topology, no web server,
 * no relays, migrations on. Used once per install or upgrade, before the application starts
 * (Helm hook Job, {@code install.sh} and {@code upgrade.sh}).
 */
public class MigrateOnly implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY = "foodstack.migrate-only";

    static boolean isEnabled(Environment environment) {
        return environment.getProperty(PROPERTY, Boolean.class, false);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!isEnabled(environment)) {
            return;
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("spring.main.web-application-type", "none");
        overrides.put("foodstack.mongodb.migrations.enabled", "true");
        overrides.put("module-switch.expose.modules", "");
        for (String module : FoodstackModules.ALL) {
            overrides.put("module-switch.modules." + module + ".mode", "local");
        }
        environment.getPropertySources().addFirst(new MapPropertySource("foodstackMigrateOnly", overrides));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
