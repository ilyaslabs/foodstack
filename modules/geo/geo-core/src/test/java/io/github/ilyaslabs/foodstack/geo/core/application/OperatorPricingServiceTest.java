package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import io.github.ilyaslabs.foodstack.geo.api.events.OperatorDeliveryPricingChanged;
import io.github.ilyaslabs.foodstack.geo.core.persistence.OperatorPricingDocument;
import io.github.ilyaslabs.foodstack.geo.core.persistence.OperatorPricingRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OperatorPricingServiceTest {

    private final OperatorPricingRepository repository = mock(OperatorPricingRepository.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final OperatorPricingService service = new OperatorPricingService(this.repository, this.outbox, this.transaction,
            Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));

    private final OperatorPricingDocument document = new OperatorPricingDocument();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.document.setVersion(2L);
        this.document.setMaxDistanceMeters(8000);
        given(this.repository.findById(OperatorPricingDocument.ID)).willReturn(Optional.of(this.document));
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        given(this.repository.save(any(OperatorPricingDocument.class))).willAnswer((invocation) -> {
            OperatorPricingDocument saved = invocation.getArgument(0);
            saved.setVersion(saved.getVersion() + 1);
            return saved;
        });
    }

    @Test
    @DisplayName("updates the pricing from the version the editor saw and publishes the change")
    void update() {
        OperatorPricing updated = this.service.update(new UpdateOperatorPricing(true, 15_000, 4_000, 10_000), 2, "admin-1");

        assertThat(updated.enabled()).isTrue();
        assertThat(updated.perKmMinor()).isEqualTo(4_000);
        assertThat(updated.version()).isEqualTo(3);
        verify(this.outbox).append(new OperatorDeliveryPricingChanged(3, "admin-1"));
    }

    @Test
    @DisplayName("refuses a stale version and invalid values")
    void rules() {
        assertThatThrownBy(() -> this.service.update(new UpdateOperatorPricing(true, 0, 0, 8000), 1, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.Conflict.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("PRICING_VERSION_CONFLICT"));
        assertThatThrownBy(() -> this.service.update(new UpdateOperatorPricing(true, -1, 0, 100), 2, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("PRICING_INVALID"));
    }

    @Test
    @DisplayName("keeps the minimum order, free threshold and peak hours, and checks them (DR-1b)")
    @SuppressWarnings("unchecked")
    void extras() {
        assertThat(this.service.get().peakMultiplierPercent()).isEqualTo(100);
        java.util.List<PeakWindow> evening = java.util.List.of(new PeakWindow(java.util.List.of(), "19:00", "22:00"));
        OperatorPricing updated = this.service.update(new UpdateOperatorPricing(true, 15_000, 4_000, 10_000, 50_000, 200_000L,
                150, evening), 2, "admin-1");
        assertThat(updated.minOrderMinor()).isEqualTo(50_000);
        assertThat(updated.freeDeliveryAboveMinor()).isEqualTo(200_000);
        assertThat(updated.peakMultiplierPercent()).isEqualTo(150);
        assertThat(updated.peakWindows()).isEqualTo(evening);

        assertThatThrownBy(() -> this.service.update(new UpdateOperatorPricing(true, 0, 0, 8000, -1, 0L, 400,
                java.util.List.of(new PeakWindow(null, "7pm", "22:00"), new PeakWindow(null, "10:00", "10:00"))), 3, "admin-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(
                    (java.util.Map<String, String>) ex.details().get("errors")).containsOnlyKeys("minOrderMinor",
                            "freeDeliveryAboveMinor", "peakMultiplierPercent", "peakWindows[0]", "peakWindows[1]"));
    }
}
