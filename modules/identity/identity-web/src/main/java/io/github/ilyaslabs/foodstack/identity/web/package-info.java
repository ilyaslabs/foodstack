/**
 * Public HTTP side of the identity module: OAuth2/OIDC endpoints, the login page and
 * {@code /api/v1/auth}, {@code /api/v1/me}, {@code /api/v1/users}. Loaded only where identity is {@code local}.
 */
@Module("identity")
package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.moduleswitch.Module;
