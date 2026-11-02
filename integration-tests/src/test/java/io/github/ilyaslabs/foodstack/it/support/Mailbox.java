package io.github.ilyaslabs.foodstack.it.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Emails the platform sent, read from Mailpit's API. */
public final class Mailbox {

    private Mailbox() {
    }

    /** Waits for an email to {@code to} whose subject contains {@code subject}; returns its plain text. */
    public static String awaitEmail(String to, String subject) {
        String[] text = new String[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Response found = RestAssured.given().baseUri(FoodstackContainers.mailpitUrl()).queryParam("query", "to:" + to)
                .get("/api/v1/search");
            List<Map<String, Object>> messages = found.path("messages");
            Map<String, Object> match = messages.stream()
                .filter((m) -> String.valueOf(m.get("Subject")).contains(subject))
                .findFirst()
                .orElse(null);
            assertThat(match).as("email to %s about %s", to, subject).isNotNull();
            text[0] = RestAssured.given().baseUri(FoodstackContainers.mailpitUrl()).get("/api/v1/message/" + match.get("ID"))
                .path("Text");
        });
        return text[0];
    }
}
