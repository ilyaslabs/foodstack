package io.github.ilyaslabs.foodstack.platform.mongo;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@SpringBootTest(classes = MongoTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class InboxGuardTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    InboxGuard testmodInbox;

    @Test
    @DisplayName("handles an event once per consumer, however often it is delivered")
    void processesOnce() {
        // given
        AtomicInteger calls = new AtomicInteger();

        // when
        boolean first = this.testmodInbox.processOnce("event-1", "search", calls::incrementAndGet);
        boolean second = this.testmodInbox.processOnce("event-1", "search", calls::incrementAndGet);
        boolean otherConsumer = this.testmodInbox.processOnce("event-1", "ledger", calls::incrementAndGet);

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(otherConsumer).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("a failed handler leaves no inbox row, so redelivery retries it")
    void failedHandlerRetries() {
        // when
        assertThatIllegalStateException().isThrownBy(() -> this.testmodInbox.processOnce("event-2", "search", () -> {
            throw new IllegalStateException("boom");
        }));
        AtomicInteger calls = new AtomicInteger();
        boolean retried = this.testmodInbox.processOnce("event-2", "search", calls::incrementAndGet);

        // then
        assertThat(retried).isTrue();
        assertThat(calls).hasValue(1);
    }
}
