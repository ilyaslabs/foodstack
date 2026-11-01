package io.github.ilyaslabs.foodstack.platform.security;

/**
 * Short-lived access tokens of the platform itself ({@code sub} {@value ModuleAccess#SERVICE_CLIENT},
 * permission {@value ModuleAccess#SERVICE}). Where identity is local it mints them directly;
 * elsewhere they come from identity's token endpoint with the client-credentials grant.
 */
public interface ServiceTokens {

    /** A valid token, cached until shortly before it expires. */
    String accessToken();
}
