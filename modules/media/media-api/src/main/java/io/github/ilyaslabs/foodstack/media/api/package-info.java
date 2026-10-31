/**
 * Internal contract of the media module: uploads, processed image variants and their URLs.
 * Other modules store only media ids and resolve them through {@link io.github.ilyaslabs.foodstack.media.api.MediaApi}.
 */
@ModuleApi("media")
package io.github.ilyaslabs.foodstack.media.api;

import io.github.ilyaslabs.moduleswitch.ModuleApi;
