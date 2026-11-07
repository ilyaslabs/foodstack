package io.github.ilyaslabs.foodstack.identity.web.dto;

import java.util.List;

/**
 * The operator's social sign-in switches (PRD ID-2, SG-6): an empty list turns the provider off.
 *
 * @param googleClientIds Google OAuth client ids (the web one first)
 * @param appleClientIds Apple Services ID (web, first) and app bundle ids
 */
public record SignInProvidersRequest(List<String> googleClientIds, List<String> appleClientIds) {
}
