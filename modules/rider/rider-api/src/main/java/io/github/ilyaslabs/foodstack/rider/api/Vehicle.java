package io.github.ilyaslabs.foodstack.rider.api;

/** How a rider travels; used for the ETA and shown to the customer. */
public enum Vehicle {

    BICYCLE,

    MOTORBIKE,

    SCOOTER,

    CAR;

    /** Average urban speed in km/h, for estimates. */
    public double urbanSpeedKmh() {
        return switch (this) {
            case BICYCLE -> 14;
            case CAR -> 22;
            default -> 25;
        };
    }
}
