package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.settings.core.persistence.DeliveryDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.DeliveryRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DeliverySettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final DeliveryRepository repository = mock(DeliveryRepository.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final SettingsCache cache = mock(SettingsCache.class);

    private DeliverySettingsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        given(this.cache.get(any(), any(), any())).willAnswer((invocation) -> ((Supplier<?>) invocation.getArgument(2)).get());
        given(this.repository.save(any())).willAnswer((invocation) -> {
            DeliveryDocument d = invocation.getArgument(0);
            return new DeliveryDocument(d.id(), d.riderPayBasisPoints(), d.riderPayMinMinor(), d.offerTimeoutSeconds(),
                    d.searchRadiusMeters(), d.searchLeadMinutes(), d.noRiderAlertSeconds(), d.proofCodeAboveMinor(),
                    d.locationRetentionDays(), d.updatedAt(), d.updatedBy(), (d.version() == null) ? 0L : d.version() + 1);
        });
        this.service = new DeliverySettingsService(this.repository, this.outbox, this.transaction, this.cache,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("starts with the PRD defaults: 70% rider pay, 30 s offers, 5 km, no delivery code")
    void defaults() {
        given(this.repository.findById(DeliveryDocument.ID)).willReturn(Optional.empty());

        DeliveryDetails details = this.service.get();

        assertThat(details.riderPayBasisPoints()).isEqualTo(7000);
        assertThat(details.offerTimeoutSeconds()).isEqualTo(30);
        assertThat(details.searchRadiusMeters()).isEqualTo(5000);
        assertThat(details.proofCodeAboveMinor()).isNull();
        assertThat(details.locationRetentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("saves new rules with SettingsChanged and evicts the cache; an old version is refused")
    void update() {
        given(this.repository.findById(DeliveryDocument.ID)).willReturn(Optional.of(DeliverySettingsService.DEFAULTS));

        DeliveryDetails updated = this.service.update(new UpdateDelivery(8000, 15000, 45, 4000, 12, 600, 500000L, 14), 0,
                "admin-1");

        assertThat(updated.version()).isZero();
        assertThat(updated.riderPayMinMinor()).isEqualTo(15000);
        assertThat(updated.proofCodeAboveMinor()).isEqualTo(500000L);
        verify(this.outbox).append(new SettingsChanged(SettingsChanged.DELIVERY, 0, "admin-1"));
        verify(this.cache).evict(SettingsChanged.DELIVERY);

        assertThatExceptionOfType(ModuleException.Conflict.class)
            .isThrownBy(() -> this.service.update(new UpdateDelivery(7000, 0, 30, 5000, 10, 300, null, 30), 3, "admin-1"));
    }

    @Test
    @DisplayName("explains every invalid field")
    @SuppressWarnings("unchecked")
    void invalid() {
        assertThatExceptionOfType(ModuleException.BadRequest.class)
            .isThrownBy(() -> this.service.update(new UpdateDelivery(10001, -1, 5, 100, 90, 10, -5L, 0), 0, "admin-1"))
            .satisfies((ex) -> assertThat((Map<String, String>) ex.details().get("errors")).containsOnlyKeys(
                    "riderPayBasisPoints", "riderPayMinMinor", "offerTimeoutSeconds", "searchRadiusMeters",
                    "searchLeadMinutes", "noRiderAlertSeconds", "proofCodeAboveMinor", "locationRetentionDays"));
        verify(this.repository, never()).save(any());
    }
}
