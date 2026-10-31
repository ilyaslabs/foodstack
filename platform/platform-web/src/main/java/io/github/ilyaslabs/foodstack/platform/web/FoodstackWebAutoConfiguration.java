package io.github.ilyaslabs.foodstack.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/** Registers the shared REST conventions in every web application. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(ProblemDetailsAdvice.class)
public final class FoodstackWebAutoConfiguration {

}
