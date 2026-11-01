package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.events.ZoneChanged;
import io.github.ilyaslabs.foodstack.geo.core.persistence.ZoneDocument;
import io.github.ilyaslabs.foodstack.geo.core.persistence.ZoneRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ZoneServiceTest {

    private static final GeoPoint STORE = new GeoPoint(31.5204, 74.3587);

    private final ZoneRepository repository = mock(ZoneRepository.class);

    private final OutboxWriter outbox = mock(OutboxWriter.class);

    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    private final ZoneService service = new ZoneService(this.repository, this.outbox, this.transaction,
            Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.transaction.execute(any())).willAnswer(
                (invocation) -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        given(this.repository.saveAll(anyList())).willAnswer((invocation) -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("replaces all zones of a branch in order, turning rings into polygons, and publishes ZoneChanged")
    void replace() {
        // when
        List<ZoneDetails> saved = this.service.replaceZones("s1", List.of(
                new ZoneInput(" Up to 3 km ", STORE, 3000, null, 10_000, 50_000, 200_000L),
                new ZoneInput("Canal side", null, null,
                        List.of(new GeoPoint(31.50, 74.30), new GeoPoint(31.50, 74.40), new GeoPoint(31.55, 74.35)), 25_000,
                        0, null)),
                "owner-1");

        // then
        assertThat(saved).extracting(ZoneDetails::name).containsExactly("Up to 3 km", "Canal side");
        assertThat(saved.getFirst().polygon()).hasSize(ZoneService.CIRCLE_POINTS);
        assertThat(saved.getFirst().radiusMeters()).isEqualTo(3000);
        assertThat(saved.get(1).polygon()).hasSize(3);
        InOrder order = inOrder(this.repository, this.outbox);
        order.verify(this.repository).deleteByStoreId("s1");
        order.verify(this.repository).saveAll(anyList());
        order.verify(this.outbox).append(new ZoneChanged("s1", 2, "owner-1"));
    }

    @Test
    @DisplayName("stores rings as closed GeoJSON polygons and reads them back without the closing point")
    void geoJsonRoundTrip() {
        List<GeoPoint> triangle = List.of(new GeoPoint(1, 2), new GeoPoint(3, 4), new GeoPoint(5, 6));
        ZoneDocument document = new ZoneDocument();
        document.setArea(ZoneService.toGeoJson(triangle));

        assertThat(document.getArea().getCoordinates().getFirst().getCoordinates()).hasSize(4);
        assertThat(ZoneService.outline(document.getArea())).isEqualTo(triangle);
    }

    @Test
    @DisplayName("rejects zones with a bad radius, too few points or negative charges")
    void validation() {
        assertThatThrownBy(() -> this.service.replaceZones("s1", List.of(
                new ZoneInput("Tiny", STORE, 50, null, 0, 0, null),
                new ZoneInput("Line", null, null, List.of(new GeoPoint(1, 1), new GeoPoint(2, 2)), 0, 0, null),
                new ZoneInput("Neg", STORE, 3000, null, -1, 0, null)), "owner-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> {
                assertThat(ex.code()).isEqualTo("ZONES_INVALID");
                assertThat(ex.getMessage()).isNotBlank();
            });
        verifyNoInteractions(this.outbox);
    }

    @Test
    @DisplayName("rejects an outline whose edges cross, and accepts a concave one")
    void crossingOutline() {
        // A bow tie: the edge (0,0)-(1,1) crosses (1,0)-(0,1).
        List<GeoPoint> bowTie = List.of(new GeoPoint(0, 0), new GeoPoint(1, 1), new GeoPoint(1, 0), new GeoPoint(0, 1));
        assertThatThrownBy(() -> this.service.replaceZones("s1",
                List.of(new ZoneInput("Bow tie", null, null, bowTie, 0, 0, null)), "owner-1"))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("ZONES_INVALID"));

        List<GeoPoint> lShape = List.of(new GeoPoint(0, 0), new GeoPoint(0, 2), new GeoPoint(1, 2), new GeoPoint(1, 1),
                new GeoPoint(2, 1), new GeoPoint(2, 0));
        assertThat(ZoneService.selfIntersects(lShape)).isFalse();
        assertThat(ZoneService.selfIntersects(bowTie)).isTrue();
        verifyNoInteractions(this.outbox);
    }
}
