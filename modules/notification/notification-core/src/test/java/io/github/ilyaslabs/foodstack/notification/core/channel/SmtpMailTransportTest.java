package io.github.ilyaslabs.foodstack.notification.core.channel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.notification.core.channel.MailTransport.MailFailure;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** The SMTP adapter against a real SMTP server (Mailpit), the lowest layer (testing strategy). */
class SmtpMailTransportTest {

    @Test
    @DisplayName("sends an HTML email with a plain-text part and UTF-8 subject")
    void sends() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(FoodstackContainers.mailpit().getHost());
        sender.setPort(FoodstackContainers.mailpit().getMappedPort(1025));
        String to = "sara-" + System.nanoTime() + "@example.com";

        new SmtpMailTransport(sender, "no-reply@foodstack.localhost", "Lahore Eats").send(to, "آرڈر 7F3A-91C2 قبول ہو گیا",
                "<p dir=\"rtl\">تیار ہو رہا ہے</p>", "تیار ہو رہا ہے");

        HttpClient http = HttpClient.newHttpClient();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String found = http.send(HttpRequest.newBuilder(URI.create(FoodstackContainers.mailpitUrl() + "/api/v1/search?query=to:"
                    + to)).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertThat(found).contains("\"total\":1").contains("آرڈر 7F3A-91C2 قبول ہو گیا").contains("Lahore Eats");
        });
    }

    @Test
    @DisplayName("an unreachable server is worth retrying; a malformed address is not")
    void failures() {
        JavaMailSenderImpl down = new JavaMailSenderImpl();
        down.setHost("localhost");
        down.setPort(1);
        assertThatThrownBy(() -> new SmtpMailTransport(down, "no-reply@foodstack.localhost", "X").send("a@example.com", "s", "h", "t"))
            .isInstanceOfSatisfying(MailFailure.class, (ex) -> assertThat(ex.isPermanent()).isFalse());
        assertThatThrownBy(() -> new SmtpMailTransport(down, "no-reply@foodstack.localhost", "X").send("not an address", "s", "h",
                "t")).isInstanceOfSatisfying(MailFailure.class, (ex) -> assertThat(ex.isPermanent()).isTrue());
    }
}
