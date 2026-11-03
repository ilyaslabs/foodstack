/**
 * Implementation of the dispatch module API. Dispatch has no synchronous API (other modules follow
 * its events), so this holds nothing yet. Loaded only where dispatch is {@code local}.
 */
@Module("dispatch")
package io.github.ilyaslabs.foodstack.dispatch.impl;

import io.github.ilyaslabs.moduleswitch.Module;
