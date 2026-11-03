package io.github.ilyaslabs.foodstack.dispatch.core.application;

import io.github.ilyaslabs.foodstack.dispatch.core.persistence.JobDocument;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.settings.api.DeliveryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class RiderPayTest {

    /** 70% of the charge, at least PKR 50. */
    private static final DeliveryView RULES = new DeliveryView(7000, 5_000, 30, 5000, 10, 300, null, 30, 0);

    @Test
    @DisplayName("a free or discounted delivery pays the operator rider on the normal charge (ilyas, 2026-11-03)")
    void freeDelivery() {
        assertThat(DispatchService.earningFor(job(0, 15_000), RiderKind.OPERATOR, RULES)).isEqualTo(10_500);
        assertThat(DispatchService.earningFor(job(5_000, 15_000), RiderKind.OPERATOR, RULES)).isEqualTo(10_500);
        assertThat(DispatchService.earningFor(job(15_000, 15_000), RiderKind.OPERATOR, RULES)).isEqualTo(10_500);
    }

    @Test
    @DisplayName("jobs made before the normal charge was kept pay on what the customer paid, with the floor; store riders get nothing")
    void olderJobsAndStoreRiders() {
        assertThat(DispatchService.earningFor(job(15_000, 0), RiderKind.OPERATOR, RULES)).isEqualTo(10_500);
        assertThat(DispatchService.earningFor(job(0, 0), RiderKind.OPERATOR, RULES)).isEqualTo(5_000);
        assertThat(DispatchService.earningFor(job(0, 15_000), RiderKind.STORE, RULES)).isZero();
    }

    private static JobDocument job(long paidMinor, long normalMinor) {
        JobDocument job = mock(JobDocument.class);
        given(job.deliveryChargeMinor()).willReturn(paidMinor);
        given(job.payBasisMinor()).willReturn(normalMinor);
        return job;
    }
}
