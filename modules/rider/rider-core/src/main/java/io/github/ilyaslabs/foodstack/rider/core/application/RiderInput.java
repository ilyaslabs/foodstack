package io.github.ilyaslabs.foodstack.rider.core.application;

import io.github.ilyaslabs.foodstack.rider.api.Vehicle;

/**
 * A new rider or changes to one. Email and password are used when adding only.
 *
 * @param email sign-in email (adding only)
 * @param initialPassword password for a new account (adding only; an existing account keeps its own)
 * @param name display name
 * @param phone phone for the store and support
 * @param vehicle vehicle
 * @param plate number plate, or {@code null}
 * @param city where the rider works (name), or {@code null} to use the chosen city's name
 * @param cityId the operator's city the rider works in, or {@code null} for any city
 * @param version the version read, when changing
 */
public record RiderInput(String email, String initialPassword, String name, String phone, Vehicle vehicle, String plate,
        String city, String cityId, Long version) {

    /** A rider without a city from the operator's list. */
    public RiderInput(String email, String initialPassword, String name, String phone, Vehicle vehicle, String plate,
            String city, Long version) {
        this(email, initialPassword, name, phone, vehicle, plate, city, null, version);
    }

    @Override
    public String toString() {
        return "RiderInput[name=" + this.name + ", vehicle=" + this.vehicle + "]";
    }
}
