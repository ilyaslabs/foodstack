package io.github.ilyaslabs.foodstack.identity.core.events;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.identity.core.application.AuditLog;
import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;

/** Keeps the back-office writes every module sends to {@code <namespace>.audit} (PRD AD-3). */
@EVL.Queue("foodstack.audit")
public class AuditListener {

    private final AuditLog log;

    private final JsonMapper jsonMapper;

    public AuditListener(AuditLog log, JsonMapper jsonMapper) {
        this.log = log;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(concurrency = "${foodstack.kafka.concurrency.identity:${foodstack.kafka.listener-concurrency:3}}",
            id = "${foodstack.namespace:foodstack}-identity-audit", groupId = "${foodstack.namespace:foodstack}-identity",
            topics = "${foodstack.namespace:foodstack}.audit", properties = "auto.offset.reset=earliest")
    @Flow(value = "identity-on-audit", label = "Store audit entries", description = "Audit entries sent by every module")
    @EVL.Job(trigger = "audit topic")
    public void onEntry(String value) {
        this.log.record(this.jsonMapper.readValue(value, AuditEntry.class));
    }
}
