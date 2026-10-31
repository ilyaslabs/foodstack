package io.github.ilyaslabs.foodstack.platform.core;

/**
 * An event a module publishes to its Kafka topic {@code foodstack.<module>.events}.
 * <p>
 * Event records live in the publishing module's {@code -api} under {@code events}. Changes are
 * additive; a breaking change gets a new version through {@link EventVersion}.
 */
public interface ModuleEvent {

    /** The aggregate the event is about; used as the Kafka key so its events stay ordered. */
    String subject();
}
