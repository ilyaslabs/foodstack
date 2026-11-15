package io.github.ilyaslabs.foodstack.rider.api;

import java.util.List;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the rider module. Errors: {@code RIDER_NOT_FOUND} (404).
 */
@EVL.Interface(value = "RiderApi", description = "The rider module API: an in-process call, or HTTP when rider runs as its own service")
public interface RiderApi {

    /** A rider by id: the rider, the platform, operator staff, or the people of a store rider's store. */
    RiderView getRider(@Param("riderId") String riderId);

    /** Online, active operator riders near a point, nearest first (dispatch matching). Platform only. */
    List<RiderCandidate> findAvailable(@Param("search") RiderSearch search);
}
