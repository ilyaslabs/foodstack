package io.github.ilyaslabs.foodstack.it.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Runs every test of the scenario class once per {@link Topology}. The class takes the topology
 * as its constructor argument and gets the running platform from {@link FoodstackPlatform#of}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedClass(name = "{0}")
@EnumSource(Topology.class)
public @interface BothTopologies {
}
