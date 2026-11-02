package io.github.ilyaslabs.foodstack.notification.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.notification.core.persistence.InboxDocument;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** The notification centre behind the bell (PRD NO-1): a user's messages, newest first, and what is unread. */
public class InboxService {

    private final MongoTemplate mongo;

    private final Clock clock;

    public InboxService(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    /**
     * A message as screens show it.
     *
     * @param id message id
     * @param template which message
     * @param title headline
     * @param body text
     * @param link app path, or {@code null}
     * @param createdAt epoch seconds
     * @param read whether it was read
     */
    public record Item(String id, String template, String title, String body, String link, long createdAt, boolean read) {
    }

    public List<Item> list(String userId, Long before, int limit) {
        Criteria criteria = Criteria.where("userId").is(userId);
        if (before != null) {
            criteria = criteria.and("createdAt").lt(Instant.ofEpochSecond(before));
        }
        return this.mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt"))
            .limit(Math.max(1, Math.min(limit, 50))), InboxDocument.class)
            .stream()
            .map((d) -> new Item(d.id(), d.template(), d.title(), d.body(), d.link(), d.createdAt().getEpochSecond(),
                    d.readAt() != null))
            .toList();
    }

    public long unread(String userId) {
        return this.mongo.count(Query.query(Criteria.where("userId").is(userId).and("readAt").is(null)), InboxDocument.class);
    }

    public void markRead(String userId, String id) {
        this.mongo.updateFirst(Query.query(Criteria.where("userId").is(userId).and("_id").is(id).and("readAt").is(null)),
                new Update().set("readAt", this.clock.instant()), InboxDocument.class);
    }

    public void markAllRead(String userId) {
        this.mongo.updateMulti(Query.query(Criteria.where("userId").is(userId).and("readAt").is(null)),
                new Update().set("readAt", this.clock.instant()), InboxDocument.class);
    }
}
