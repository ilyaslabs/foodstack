package io.github.ilyaslabs.foodstack.order.core.application;

/**
 * Who made a change: shown on the timeline (PRD 3: every transition is attributed to an actor).
 *
 * @param role {@code CUSTOMER}, {@code STORE}, {@code SUPPORT}, {@code RIDER} or {@code SYSTEM}
 * @param id the user, or {@code null} for the system
 */
public record Actor(String role, String id) {

    public static final String CUSTOMER = "CUSTOMER";

    public static final String STORE = "STORE";

    public static final String SUPPORT = "SUPPORT";

    public static final String SYSTEM = "SYSTEM";

    /** A rider's step, reported through dispatch. */
    public static final String RIDER = "RIDER";

    public static Actor customer(String id) {
        return new Actor(CUSTOMER, id);
    }

    public static Actor store(String id) {
        return new Actor(STORE, id);
    }

    public static Actor support(String id) {
        return new Actor(SUPPORT, id);
    }

    public static Actor rider(String id) {
        return new Actor(RIDER, id);
    }

    /** From an event actor such as {@code rider:<userId>}, {@code support:<userId>} or {@code system}. */
    public static Actor fromEvent(String actor) {
        if (actor == null || actor.isBlank()) {
            return system();
        }
        int colon = actor.indexOf(':');
        String role = ((colon < 0) ? actor : actor.substring(0, colon)).toUpperCase(java.util.Locale.ROOT);
        String id = (colon < 0) ? null : actor.substring(colon + 1);
        return switch (role) {
            case CUSTOMER, STORE, SUPPORT, RIDER -> new Actor(role, id);
            default -> system();
        };
    }

    public static Actor system() {
        return new Actor(SYSTEM, null);
    }

    /** As carried in events, such as {@code store:<userId>} or {@code system}. */
    public String asEventActor() {
        return (this.id == null) ? this.role.toLowerCase(java.util.Locale.ROOT)
                : this.role.toLowerCase(java.util.Locale.ROOT) + ":" + this.id;
    }
}
