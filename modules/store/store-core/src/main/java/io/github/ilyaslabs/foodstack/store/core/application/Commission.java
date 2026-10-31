package io.github.ilyaslabs.foodstack.store.core.application;

/**
 * Commission the operator takes per order (PRD ST-1): a percentage, a fixed amount, or both.
 *
 * @param percentBasisPoints percentage in hundredths of a percent (1500 = 15%)
 * @param fixedMinor fixed amount per order in minor units
 */
public record Commission(int percentBasisPoints, long fixedMinor) {
}
