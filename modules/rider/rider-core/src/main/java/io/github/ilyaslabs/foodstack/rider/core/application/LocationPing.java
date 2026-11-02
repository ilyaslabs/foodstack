package io.github.ilyaslabs.foodstack.rider.core.application;

/**
 * One position from the rider app.
 *
 * @param lat latitude
 * @param lng longitude
 * @param accuracyMeters reported accuracy, or {@code null}
 * @param at when the phone took it, epoch seconds; {@code null} for now
 */
public record LocationPing(double lat, double lng, Double accuracyMeters, Long at) {
}
