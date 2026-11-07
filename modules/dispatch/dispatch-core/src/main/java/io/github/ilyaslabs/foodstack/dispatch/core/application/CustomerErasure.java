package io.github.ilyaslabs.foodstack.dispatch.core.application;

import io.github.ilyaslabs.foodstack.dispatch.core.persistence.JobDocument;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Anonymises a deleted customer's delivery jobs (PRD ID-7, on {@code UserDeleted}): the jobs keep
 * the rider's pay, cash and distance; the customer's name, phone, address line and directions go,
 * and the drop-off point is rounded to about a kilometre. Safe to run again.
 */
public class CustomerErasure {

    public static final String DELETED_NAME = "Deleted user";

    private final MongoTemplate mongo;

    public CustomerErasure(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** @return how many jobs were anonymised */
    public int erase(String customerId) {
        int erased = 0;
        for (JobDocument job : this.mongo.find(Query.query(Criteria.where("customerId").is(customerId)), JobDocument.class)) {
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id())),
                    new Update().set("customerName", DELETED_NAME)
                        .unset("contactPhoneCipher")
                        .unset("dropoffAddress")
                        .unset("instructions")
                        .set("dropoffLat", round(job.dropoffLat()))
                        .set("dropoffLng", round(job.dropoffLng())),
                    JobDocument.class);
            erased++;
        }
        return erased;
    }

    static double round(double degrees) {
        return Math.round(degrees * 100) / 100.0;
    }
}
