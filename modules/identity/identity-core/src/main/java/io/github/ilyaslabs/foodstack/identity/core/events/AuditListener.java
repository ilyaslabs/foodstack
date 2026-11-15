package io.github.ilyaslabs.foodstack.identity.core.events;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.identity.core.application.AuditLog;
import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Keeps the back-office writes every module sends to {@code <namespace>.audit} (PRD AD-3). */
public class AuditListener {

    private final AuditLog log;

    private final JsonMapper jsonMapper;

    public AuditListener(AuditLog log, JsonMapper jsonMapper) {
        this.log = log;
        this.jsonMapper = jsonMapper;
    }

    @Flow(value = "audit-collect", label = "Keep back-office writes in the audit log", description = "Every module sends its back-office writes to the audit topic")
    @EVL.Job(trigger = "<ns>.audit")
    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.identity:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-identity-audit", groupId = "${foodstack.namespace:foodstack}-identity",
            topics = "${foodstack.namespace:foodstack}.audit", properties = "auto.offset.reset=earliest")
    public void onEntry(String value) {
        this.log.record(this.jsonMapper.readValue(value, AuditEntry.class));
    }
}
