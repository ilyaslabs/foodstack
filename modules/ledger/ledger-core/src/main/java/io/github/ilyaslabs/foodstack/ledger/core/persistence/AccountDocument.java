package io.github.ilyaslabs.foodstack.ledger.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A branch's or a rider's account in {@code ledger_accounts}, with the name shown on reports
 * (shard key {@code { _id: hashed }}).
 *
 * @param id the account, such as {@code store:<id>}
 * @param partyType {@code STORE} or {@code RIDER}
 * @param partyId the branch or the rider
 * @param name the branch or rider name as last seen
 * @param brandId the branch's brand, or {@code null}
 * @param currency ISO 4217 code
 * @param createdAt first posting
 */
@Document("ledger_accounts")
public record AccountDocument(@Id String id, String partyType, String partyId, String name, String brandId, String currency,
        Instant createdAt) {
}
