package io.github.ilyaslabs.foodstack;

import java.util.Arrays;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/** Refuses to start with demo data in production (TDD 16). */
public class SeedGuard implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean seeding = Arrays.asList(environment.getActiveProfiles()).contains("test-seed");
        if (seeding && "production".equals(environment.getProperty("foodstack.environment"))) {
            throw new IllegalStateException("The test-seed profile cannot run with foodstack.environment=production");
        }
    }
}
