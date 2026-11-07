package io.github.ilyaslabs.foodstack.store.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.ilyaslabs.foodstack.store.core.application.Commission;
import io.github.ilyaslabs.foodstack.store.core.application.CommissionChange;

/**
 * Commission over time (PRD FI-2, BB-8): a starting value plus dated changes. An order takes the
 * commission in force when it is placed, so a change set for next month leaves today's orders alone.
 */
public final class CommissionSchedule {

    /** Kept per brand or branch; older changes beyond this are dropped. */
    public static final int MAX_CHANGES = 100;

    private CommissionSchedule() {
    }

    /** The commission in force at {@code at}: the last change that started by then, else {@code initial}. */
    public static Commission at(Commission initial, List<CommissionChange> changes, Instant at) {
        Commission current = initial;
        for (CommissionChange change : sorted(changes)) {
            if (change.effectiveFrom().isAfter(at)) {
                break;
            }
            current = change.commission();
        }
        return current;
    }

    /**
     * The changes with {@code change} added. A change replaces every change planned on or after its
     * start, so planning again simply overwrites the plan.
     */
    public static List<CommissionChange> plan(List<CommissionChange> changes, CommissionChange change) {
        List<CommissionChange> result = new ArrayList<>();
        for (CommissionChange existing : sorted(changes)) {
            if (existing.effectiveFrom().isBefore(change.effectiveFrom())) {
                result.add(existing);
            }
        }
        result.add(change);
        while (result.size() > MAX_CHANGES) {
            result.removeFirst();
        }
        return result;
    }

    private static List<CommissionChange> sorted(List<CommissionChange> changes) {
        if (changes == null) {
            return List.of();
        }
        return changes.stream().sorted(Comparator.comparing(CommissionChange::effectiveFrom)).toList();
    }
}
