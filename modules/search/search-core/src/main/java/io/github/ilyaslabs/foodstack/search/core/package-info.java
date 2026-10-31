/**
 * Store discovery (TDD 11): a read model in Elasticsearch fed by store and geo events, re-reading
 * the source through {@code StoreApi} and {@code GeoApi} on every change.
 */
@Module("search")
package io.github.ilyaslabs.foodstack.search.core;

import io.github.ilyaslabs.moduleswitch.Module;
