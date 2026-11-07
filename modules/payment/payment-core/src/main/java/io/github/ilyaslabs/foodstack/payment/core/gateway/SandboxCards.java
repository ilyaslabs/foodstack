package io.github.ilyaslabs.foodstack.payment.core.gateway;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.platform.core.Ids;
import org.bson.Document;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Where the sandbox gateway keeps "saved cards" ({@code payment_sandbox_cards}), standing in for a
 * real gateway's vault in development, tests and demos. Only the face of a pretend card is kept.
 */
public class SandboxCards {

    static final String COLLECTION = "payment_sandbox_cards";

    private final MongoTemplate mongo;

    private final Clock clock;

    public SandboxCards(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Keeps a card for a customer; the same last four digits are kept once. */
    public CardGateway.SavedCard save(String customer, String last4) {
        Document existing = this.mongo.findOne(
                Query.query(Criteria.where("customer").is(customer).and("last4").is(last4)), Document.class, COLLECTION);
        if (existing != null) {
            return card(existing);
        }
        Instant now = this.clock.instant();
        int year = now.atZone(java.time.ZoneOffset.UTC).getYear() + 4;
        Document card = new Document("_id", "sbx_pm_" + Ids.newId()).append("customer", customer)
            .append("brand", last4.equals("4444") ? "mastercard" : "visa")
            .append("last4", last4)
            .append("expMonth", 12)
            .append("expYear", year)
            .append("createdAt", java.util.Date.from(now));
        this.mongo.insert(card, COLLECTION);
        return card(card);
    }

    public List<CardGateway.SavedCard> list(String customer) {
        return this.mongo
            .find(Query.query(Criteria.where("customer").is(customer)).with(Sort.by("createdAt")), Document.class, COLLECTION)
            .stream()
            .map(SandboxCards::card)
            .toList();
    }

    public void remove(String customer, String cardId) {
        this.mongo.remove(Query.query(Criteria.where("_id").is(cardId).and("customer").is(customer)), COLLECTION);
    }

    public void removeAll(String customer) {
        this.mongo.remove(Query.query(Criteria.where("customer").is(customer)), COLLECTION);
    }

    private static CardGateway.SavedCard card(Document d) {
        return new CardGateway.SavedCard(d.getString("_id"), d.getString("brand"), d.getString("last4"),
                d.getInteger("expMonth", 12), d.getInteger("expYear", 2030));
    }
}
