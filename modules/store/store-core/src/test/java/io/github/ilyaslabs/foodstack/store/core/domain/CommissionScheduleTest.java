package io.github.ilyaslabs.foodstack.store.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.ilyaslabs.foodstack.store.core.application.Commission;
import io.github.ilyaslabs.foodstack.store.core.application.CommissionChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommissionScheduleTest {

    private static final Instant JAN = Instant.parse("2027-01-01T00:00:00Z");

    private static final Instant FEB = Instant.parse("2027-02-01T00:00:00Z");

    private static final Instant MAR = Instant.parse("2027-03-01T00:00:00Z");

    private static final Commission START = new Commission(1500, 0);

    @Test
    @DisplayName("an order takes the commission in force when it is placed")
    void inForce() {
        List<CommissionChange> changes = List.of(change(1200, MAR), change(1000, FEB));

        assertThat(CommissionSchedule.at(START, changes, JAN)).isEqualTo(START);
        assertThat(CommissionSchedule.at(START, changes, FEB)).isEqualTo(new Commission(1000, 0));
        assertThat(CommissionSchedule.at(START, changes, FEB.plusSeconds(86_400))).isEqualTo(new Commission(1000, 0));
        assertThat(CommissionSchedule.at(START, changes, MAR)).isEqualTo(new Commission(1200, 0));
        assertThat(CommissionSchedule.at(START, null, MAR)).isEqualTo(START);
    }

    @Test
    @DisplayName("planning a change replaces every change planned on or after its start")
    void replan() {
        List<CommissionChange> changes = CommissionSchedule.plan(List.of(change(1000, FEB), change(1200, MAR)),
                change(900, FEB));

        assertThat(changes).extracting((c) -> c.commission().percentBasisPoints()).containsExactly(900);
        assertThat(CommissionSchedule.plan(changes, change(800, MAR)))
            .extracting((c) -> c.commission().percentBasisPoints())
            .containsExactly(900, 800);
    }

    @Test
    @DisplayName("keeps at most the last hundred changes")
    void bounded() {
        List<CommissionChange> changes = new ArrayList<>();
        for (int day = 0; day < CommissionSchedule.MAX_CHANGES + 5; day++) {
            changes = CommissionSchedule.plan(changes, change(day, JAN.plusSeconds(day * 86_400L)));
        }

        assertThat(changes).hasSize(CommissionSchedule.MAX_CHANGES);
        assertThat(changes.getFirst().commission().percentBasisPoints()).isEqualTo(5);
    }

    private static CommissionChange change(int basisPoints, Instant from) {
        return new CommissionChange(new Commission(basisPoints, 0), from, "admin-1", JAN);
    }
}
