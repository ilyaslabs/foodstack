package io.github.ilyaslabs.foodstack.order.core.application;

/**
 * Who made a change: shown on the timeline (PRD 3: every transition is attributed to an actor).
 *
 * @param role {@code CUSTOMER}, {@code STORE}, {@code SUPPORT} or {@code SYSTEM}
 * @param id the user, or {@code null} for the system
 */
public record Actor(String role, String id) {

    public static final String CUSTOMER = "CUSTOMER";

    public static final String STORE = "STORE";

    public static final String SUPPORT = "SUPPORT";

    public static final String SYSTEM = "SYSTEM";

    public static Actor customer(String id) {
        return new Actor(CUSTOMER, id);
    }

    public static Actor store(String id) {
        return new Actor(STORE, id);
    }

    public static Actor support(String id) {
        return new Actor(SUPPORT, id);
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
