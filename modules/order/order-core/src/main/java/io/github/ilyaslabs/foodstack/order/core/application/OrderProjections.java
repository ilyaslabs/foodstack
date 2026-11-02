package io.github.ilyaslabs.foodstack.order.core.application;

import java.util.Map;

import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderSummaryDocument;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Keeps the order lists ({@code order_by_customer}, {@code order_by_store}, TDD 6.3) in step with
 * {@code order_orders}. Each refresh copies the order as it is now, guarded by its revision, so
 * events arriving twice or out of order never put an older state back.
 */
public class OrderProjections {

    private final MongoTemplate mongo;

    public OrderProjections(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void refresh(String orderId) {
        OrderDocument order = this.mongo.findById(orderId, OrderDocument.class);
        if (order != null) {
            write(order);
        }
    }

    /** Rebuilds both lists from the orders; returns how many orders were copied. */
    public long rebuild() {
        long[] count = { 0 };
        try (var orders = this.mongo.stream(new Query(), OrderDocument.class)) {
            orders.forEach((order) -> {
                write(order);
                count[0]++;
            });
        }
        return count[0];
    }

    private void write(OrderDocument o) {
        OrderSummaryDocument row = summary(o);
        upsert(OrderSummaryDocument.BY_CUSTOMER, Criteria.where("customerId").is(o.customerId()), row);
        upsert(OrderSummaryDocument.BY_STORE,
                Criteria.where("storeId").is(o.store().storeId()).and("placedAt").is(o.placedAt()), row);
    }

    /**
     * Inserts the row, or replaces it when it is older. The shard key is part of the filter, so each
     * write goes to one shard.
     */
    private void upsert(String collection, Criteria shardKey, OrderSummaryDocument row) {
        org.bson.Document document = new org.bson.Document();
        this.mongo.getConverter().write(row, document);
        document.remove("_id");
        document.remove("_class");
        Update update = new Update();
        document.forEach(update::set);
        Query newer = Query.query(Criteria.where("_id").is(row.id()).andOperator(shardKey))
            .addCriteria(Criteria.where("revision").lt(row.revision()));
        if (this.mongo.updateFirst(newer, update, collection).getMatchedCount() > 0) {
            return;
        }
        if (this.mongo.exists(Query.query(Criteria.where("_id").is(row.id()).andOperator(shardKey)), collection)) {
            return;
        }
        try {
            this.mongo.insert(row, collection);
        }
        catch (org.springframework.dao.DuplicateKeyException ex) {
            this.mongo.updateFirst(newer, update, collection);
        }
    }

    static OrderSummaryDocument summary(OrderDocument o) {
        return new OrderSummaryDocument(o.id(), o.number(), o.customerId(), o.customerName(), o.store().storeId(), o.store().name(),
                o.store().slug(), o.store().logoUrls(), o.status(), o.fulfilment(), o.paymentMethod(),
                o.lines().stream().limit(3).map(OrderDocument.Line::name).map(Map::copyOf).toList(),
                o.lines().stream().mapToInt(OrderDocument.Line::quantity).sum(), o.totals().totalMinor(), o.currency(),
                o.placedAt(), o.updatedAt(), o.revision());
    }
}
