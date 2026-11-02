package io.github.ilyaslabs.foodstack.rider.core.application;

import java.util.List;

/**
 * A rider's earnings over recent days (PRD DR-7), in the rider's time zone.
 *
 * @param currency ISO 4217 code of the amounts, or {@code null} when there is nothing yet
 * @param today today's totals
 * @param days one row per day, newest first, including days with nothing
 * @param recent latest deliveries, newest first
 */
public record Earnings(String currency, Day today, List<Day> days, List<Row> recent) {

    /**
     * @param date ISO date
     * @param deliveries deliveries
     * @param earningMinor delivery pay
     * @param tipMinor tips
     * @param cashCollectedMinor cash taken at the door, to hand in
     */
    public record Day(String date, int deliveries, long earningMinor, long tipMinor, long cashCollectedMinor) {
    }

    /**
     * @param orderId order
     * @param orderNumber order number
     * @param storeName store
     * @param earningMinor delivery pay
     * @param tipMinor tip
     * @param cashCollectedMinor cash taken
     * @param distanceMeters store to customer
     * @param at delivered at, epoch seconds
     */
    public record Row(String orderId, String orderNumber, String storeName, long earningMinor, long tipMinor,
            long cashCollectedMinor, long distanceMeters, long at) {
    }
}
