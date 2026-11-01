package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.settings.core.persistence.OrderingDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.OrderingRepository;
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

class OrderingSettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final OrderingRepository repository = mock(OrderingRepository.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final SettingsCache cache = mock(SettingsCache.class);

    private OrderingSettingsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        given(this.cache.get(any(), any(), any())).willAnswer((invocation) -> ((Supplier<?>) invocation.getArgument(2)).get());
        given(this.repository.save(any())).willAnswer((invocation) -> {
            OrderingDocument d = invocation.getArgument(0);
            return new OrderingDocument(d.id(), d.cashEnabled(), d.cardEnabled(), d.serviceFeeMinor(), d.serviceFeeBasisPoints(),
                    d.taxBasisPoints(), d.tipsEnabled(), d.acceptTimeoutSeconds(), d.updatedAt(), d.updatedBy(),
                    (d.version() == null) ? 0L : d.version() + 1);
        });
        this.service = new OrderingSettingsService(this.repository, this.outbox, this.transaction, this.cache,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("falls back to cash only, 3 minutes to accept, when an older install has no ordering document")
    void defaults() {
        given(this.repository.findById(OrderingDocument.ID)).willReturn(Optional.empty());

        OrderingDetails details = this.service.get();

        assertThat(details.cashEnabled()).isTrue();
        assertThat(details.cardEnabled()).isFalse();
        assertThat(details.acceptTimeoutSeconds()).isEqualTo(180);
        assertThat(details.version()).isZero();
    }

    @Test
    @DisplayName("saves new rules with SettingsChanged in one transaction, then evicts the cache")
    void update() {
        given(this.repository.findById(OrderingDocument.ID)).willReturn(Optional.of(document(2L)));

        OrderingDetails updated = this.service.update(new UpdateOrdering(true, true, 4900, 250, 1600, true, 300), 2, "admin-1");

        assertThat(updated.version()).isEqualTo(3);
        assertThat(updated.serviceFeeMinor()).isEqualTo(4900);
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        verify(this.outbox).append(new SettingsChanged(SettingsChanged.ORDERING, 3, "admin-1"));
        verify(this.cache).evict(SettingsChanged.ORDERING);
    }

    @Test
    @DisplayName("rejects an edit based on an old version")
    void conflict() {
        given(this.repository.findById(OrderingDocument.ID)).willReturn(Optional.of(document(5L)));

        assertThatExceptionOfType(ModuleException.Conflict.class)
            .isThrownBy(() -> this.service.update(new UpdateOrdering(true, false, 0, 0, 0, true, 180), 4, "admin-1"))
            .satisfies((ex) -> assertThat(ex.code()).isEqualTo("SETTINGS_VERSION_CONFLICT"));
        verify(this.repository, never()).save(any());
    }

    @Test
    @DisplayName("explains every invalid field")
    @SuppressWarnings("unchecked")
    void invalid() {
        assertThatExceptionOfType(ModuleException.BadRequest.class)
            .isThrownBy(() -> this.service.update(new UpdateOrdering(false, false, -1, 6000, 10001, true, 30), 0, "admin-1"))
            .satisfies((ex) -> assertThat((Map<String, String>) ex.details().get("errors")).containsOnlyKeys("cashEnabled",
                    "serviceFeeMinor", "serviceFeeBasisPoints", "taxBasisPoints", "acceptTimeoutSeconds"));
    }

    private static OrderingDocument document(long version) {
        return new OrderingDocument(OrderingDocument.ID, true, false, 0, 0, 0, true, 180, null, null, version);
    }
}
