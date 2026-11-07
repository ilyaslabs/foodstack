package io.github.ilyaslabs.foodstack.identity.core.application;

import java.util.List;

/** The client ids that turn on Google and Apple sign-in; an empty list means the button is off. */
public record SignInProviders(List<String> googleClientIds, List<String> appleClientIds) {
}
