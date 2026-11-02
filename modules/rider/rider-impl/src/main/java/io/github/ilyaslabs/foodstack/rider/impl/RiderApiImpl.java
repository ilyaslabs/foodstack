package io.github.ilyaslabs.foodstack.rider.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.rider.api.RiderApi;
import io.github.ilyaslabs.foodstack.rider.api.RiderCandidate;
import io.github.ilyaslabs.foodstack.rider.api.RiderSearch;
import io.github.ilyaslabs.foodstack.rider.api.RiderView;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderDetails;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderService;

import org.springframework.stereotype.Component;

/**
 * {@link RiderApi} for other modules. Reading a rider follows the same rules as the screens (the
 * rider, operator staff, the rider's store); finding riders for a job is for the platform and
 * dispatch staff only (TDD 9.1).
 */
@Component
class RiderApiImpl implements RiderApi {

    private final RiderService riders;

    private final RiderActivity activity;

    RiderApiImpl(RiderService riders, RiderActivity activity) {
        this.riders = riders;
        this.activity = activity;
    }

    @Override
    public RiderView getRider(String riderId) {
        ModuleAccess.requireAuthenticated();
        RiderDetails r = ModuleAccess.isService() ? this.riders.details(riderId) : this.riders.get(riderId);
        return new RiderView(r.id(), r.kind(), r.storeId(), r.name(), r.vehicle(), r.status(), r.online(), r.lat(), r.lng(),
                r.lastSeenAt());
    }

    @Override
    public List<RiderCandidate> findAvailable(RiderSearch search) {
        ModuleAccess.requireServiceOr(RiderService.DISPATCH);
        return this.activity.findAvailable(search);
    }
}
