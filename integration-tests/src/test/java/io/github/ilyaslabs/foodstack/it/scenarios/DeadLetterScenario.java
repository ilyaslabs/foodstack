package io.github.ilyaslabs.foodstack.it.scenarios;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.it.support.ApiClient;
import io.github.ilyaslabs.foodstack.it.support.FoodstackPlatform;
import io.github.ilyaslabs.foodstack.it.support.Topology;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.restassured.response.Response;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Failed events (TDD 7.1, gap G1): an event its consumer cannot handle lands in the back office's
 * list, with the module and consumer named; replaying it sends it to that consumer only, and a
 * replay that fails again comes back as a new entry; an operator can dismiss one.
 */
abstract class DeadLetterScenario {

    private final FoodstackPlatform platform;

    DeadLetterScenario(Topology topology) {
        this.platform = FoodstackPlatform.of(topology);
    }

    @Test
    @DisplayName("a failed event is listed, replayed to the consumer that failed, and dismissed")
    void deadLetters() throws Exception {
        ApiClient admin = this.platform.client().logIn(FoodstackPlatform.ADMIN_EMAIL, FoodstackPlatform.ADMIN_PASSWORD);
        String marker = "it-poison-" + System.nanoTime();

        // given: a ledger event the notification module cannot read
        publish(this.platform.namespace() + ".ledger.events", marker, "{ not json " + marker);

        // then: it is listed once for each module that reads ledger events and failed on it
        Map<String, Object> failed = awaitOpen(admin, marker, "notification", null);
        awaitOpen(admin, marker, "payment", null);
        assertThat(failed.get("module")).isEqualTo("ledger");
        assertThat((String) failed.get("error")).isNotBlank();
        String id = (String) failed.get("id");
        assertThat(admin.get("/api/v1/settings/dead-letters/" + id + "/event").<String>path("value"))
            .isEqualTo("{ not json " + marker);

        // when: replayed, it fails again in notification only and comes back as a new entry
        admin.postJson("/api/v1/settings/dead-letters/" + id + "/replay", Map.of())
            .then().log().ifValidationFails().statusCode(200);
        assertThat(entry(admin, id).get("status")).isEqualTo("REPLAYED");
        Map<String, Object> again = awaitOpen(admin, marker, "notification", id);
        // payment already handled (and failed on) the original, so the replay skipped it
        assertThat(matching(admin, marker, "payment")).hasSize(1);
        Response twice = admin.postJson("/api/v1/settings/dead-letters/" + id + "/replay", Map.of());
        assertThat(twice.statusCode()).isEqualTo(409);

        // when: dismissed
        String againId = (String) again.get("id");
        admin.postJson("/api/v1/settings/dead-letters/" + againId + "/dismiss", Map.of())
            .then().log().ifValidationFails().statusCode(200);

        // then
        assertThat(entry(admin, againId).get("status")).isEqualTo("DISMISSED");
        assertThat(this.platform.client().get("/api/v1/settings/dead-letters").statusCode()).isEqualTo(401);
    }

    /** The open entry of {@code consumer} for the marker, other than {@code except}. */
    private Map<String, Object> awaitOpen(ApiClient admin, String marker, String consumer, String except) {
        return await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            List<Map<String, Object>> open = admin.get("/api/v1/settings/dead-letters?status=OPEN").path("$");
            return open.stream()
                .filter((e) -> !e.get("id").equals(except) && consumer.equals(e.get("consumer")))
                .filter((e) -> holds(admin, e, marker))
                .findFirst()
                .orElse(null);
        }, (found) -> found != null);
    }

    private static List<Map<String, Object>> matching(ApiClient admin, String marker, String consumer) {
        List<Map<String, Object>> all = admin.get("/api/v1/settings/dead-letters").path("$");
        return all.stream().filter((e) -> consumer.equals(e.get("consumer")) && holds(admin, e, marker)).toList();
    }

    private static boolean holds(ApiClient admin, Map<String, Object> entry, String marker) {
        String value = admin.get("/api/v1/settings/dead-letters/" + entry.get("id") + "/event").path("value");
        return value != null && value.contains(marker);
    }

    private static Map<String, Object> entry(ApiClient admin, String id) {
        List<Map<String, Object>> all = admin.get("/api/v1/settings/dead-letters").path("$");
        return all.stream().filter((e) -> e.get("id").equals(id)).findFirst().orElseThrow();
    }

    private static void publish(String topic, String key, String value) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                FoodstackContainers.kafka().getBootstrapServers(), ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }
}
