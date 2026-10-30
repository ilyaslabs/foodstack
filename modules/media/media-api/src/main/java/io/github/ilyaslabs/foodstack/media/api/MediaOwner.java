package io.github.ilyaslabs.foodstack.media.api;

/**
 * The entity that uses an asset, for example module {@code settings}, entity {@code branding}.
 *
 * @param module the owning module
 * @param entityId the entity inside that module
 */
public record MediaOwner(String module, String entityId) {
}
