package io.github.ilyaslabs.foodstack.platform.core;

/** Where audit entries go: the identity module's audit log, through Kafka in a running deployment. */
public interface AuditSink {

    /** Records the entry; never throws, a failure is logged with the entry instead. */
    void record(AuditEntry entry);
}
