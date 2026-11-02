/**
 * Contract of the dispatch module: the delivery job of each delivery order, offers to operator
 * riders, assignment of store riders, pickup and proof of delivery (PRD 4.6, TDD 5.6). Other
 * modules follow its events; nobody calls dispatch synchronously.
 */
@ModuleApi("dispatch")
package io.github.ilyaslabs.foodstack.dispatch.api;

import io.github.ilyaslabs.moduleswitch.ModuleApi;
