package io.github.ilyaslabs.foodstack.identity.web.dto;

import java.util.List;

/**
 * Social sign-in for the apps: which buttons to show and the client id each provider's script needs.
 *
 * @param googleClientIds empty when Google is off; the first is the web client
 * @param appleClientIds empty when Apple is off; the first is the web Services ID
 */
public record SignInProvidersResponse(List<String> googleClientIds, List<String> appleClientIds) {
}
