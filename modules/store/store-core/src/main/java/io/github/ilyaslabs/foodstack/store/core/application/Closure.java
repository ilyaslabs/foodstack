package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.LocalDate;

/**
 * A holiday closure, both days included (PRD ST-2).
 *
 * @param from first closed day
 * @param to last closed day
 * @param reason optional reason
 */
public record Closure(LocalDate from, LocalDate to, String reason) {
}
