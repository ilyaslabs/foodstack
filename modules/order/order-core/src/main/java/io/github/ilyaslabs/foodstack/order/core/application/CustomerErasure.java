package io.github.ilyaslabs.foodstack.order.core.application;

import java.util.List;

import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderSummaryDocument;
import org.bson.Document;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Anonymises a deleted customer's orders (PRD ID-7, on {@code UserDeleted}). The orders stay, with
 * their money, for the ledger, tax and the store's history; the name becomes "Deleted user", the
 * phone, address line, rider directions and kitchen notes go, and the drop-off point is rounded to
 * about a kilometre. Safe to run again.
 */
public class CustomerErasure {

    public static final String DELETED_NAME = "Deleted user";

    private final MongoTemplate mongo;

    private final OrderProjections projections;

    public CustomerErasure(MongoTemplate mongo, OrderProjections projections) {
        this.mongo = mongo;
        this.projections = projections;
    }

    /** @return how many orders were anonymised */
    public int erase(String customerId) {
        Query mine = Query.query(Criteria.where("customerId").is(customerId));
        mine.fields().include("_id");
        List<String> ids = this.mongo.find(mine, Document.class, OrderSummaryDocument.BY_CUSTOMER)
            .stream()
            .map((row) -> row.getString("_id"))
            .toList();
        int erased = 0;
        for (String id : ids) {
            OrderDocument order = this.mongo.findById(id, OrderDocument.class);
            if (order == null || !customerId.equals(order.customerId())) {
                continue;
            }
            Update update = new Update().set("customerName", DELETED_NAME)
                .unset("contactPhone")
                .set("lines.$[].note", null)
                .inc("revision", 1);
            if (order.destination() != null) {
                update.set("destination.addressLine", null)
                    .set("destination.instructions", null)
                    .set("destination.lat", round(order.destination().lat()))
                    .set("destination.lng", round(order.destination().lng()));
            }
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), update, OrderDocument.class);
            this.projections.refresh(id);
            erased++;
        }
        return erased;
    }

    /** Two decimals: about 1.1 km, enough for distance reports and no longer a home. */
    static double round(double degrees) {
        return Math.round(degrees * 100) / 100.0;
    }
}
