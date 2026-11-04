package io.github.ilyaslabs.foodstack.settings.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachingSettingsApiTest {

    @Test
    @DisplayName("answers from memory for the time to live, then asks the settings module again")
    void keepsAnswers() {
        // given
        AtomicInteger calls = new AtomicInteger();
        SettingsApi remote = new SettingsApi() {
            @Override
            public BrandingView getBranding() {
                return null;
            }

            @Override
            public LocaleView getLocale() {
                calls.incrementAndGet();
                return new LocaleView("en", List.of("en"), "PKR", "Asia/Karachi", calls.get());
            }

            @Override
            public OrderingView getOrdering() {
                return null;
            }

            @Override
            public DeliveryView getDelivery() {
                return null;
            }
        };
        MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
        SettingsApi cached = new CachingSettingsApi(remote, Duration.ofSeconds(30), clock);

        // when
        long first = cached.getLocale().version();
        clock.now = clock.now.plusSeconds(29);
        long within = cached.getLocale().version();
        clock.now = clock.now.plusSeconds(2);
        long after = cached.getLocale().version();

        // then
        assertThat(first).isEqualTo(1);
        assertThat(within).isEqualTo(1);
        assertThat(after).isEqualTo(2);
        assertThat(calls).hasValue(2);
    }

    private static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
