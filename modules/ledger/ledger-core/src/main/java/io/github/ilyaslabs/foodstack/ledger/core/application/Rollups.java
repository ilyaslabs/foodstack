package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.ledger.core.domain.Accounts;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.DailyDocument;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.TransactionDocument;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the daily roll-ups that reports read (TDD 6.4: reports from daily roll-ups, never live
 * sums over orders). Each run takes the transactions of this replica's work buckets that are not
 * rolled up yet, and for each (bucket, day) they touch rebuilds that day's rows from every rolled-up
 * transaction of it, in one database transaction with marking them rolled up. A row is rebuilt,
 * never added to, so a run that fails half way, or runs twice, leaves the rows right.
 */
public class Rollups {

    /** Transactions taken per run. */
    static final int BATCH = 2_000;

    private final MongoTemplate mongo;

    private final TransactionTemplate transactions;

    public Rollups(MongoTemplate mongo, TransactionTemplate transactions) {
        this.mongo = mongo;
        this.transactions = transactions;
    }

    /** Rolls up what is new in the given buckets ({@code null} = every bucket); returns how many transactions. */
    public int rollUp(Set<Integer> buckets) {
        Criteria fresh = Criteria.where("rolled").is(false);
        if (buckets != null) {
            fresh = fresh.and("bucket").in(buckets);
        }
        Query query = Query.query(fresh).limit(BATCH);
        query.fields().include("_id", "bucket", "day");
        List<Document> found = this.mongo.find(query, Document.class, this.mongo.getCollectionName(TransactionDocument.class));
        Map<String, List<String>> byBucketDay = new LinkedHashMap<>();
        for (Document t : found) {
            byBucketDay.computeIfAbsent(t.getInteger("bucket") + "|" + t.getString("day"), (k) -> new ArrayList<>())
                .add(t.getString("_id"));
        }
        for (Map.Entry<String, List<String>> entry : byBucketDay.entrySet()) {
            String[] key = entry.getKey().split("\\|", 2);
            int bucket = Integer.parseInt(key[0]);
            String day = key[1];
            this.transactions.executeWithoutResult((status) -> rebuild(bucket, day, entry.getValue()));
        }
        return found.size();
    }

    private void rebuild(int bucket, String day, List<String> ids) {
        this.mongo.updateMulti(Query.query(Criteria.where("_id").in(ids).and("rolled").is(false)),
                new Update().set("rolled", true), TransactionDocument.class);
        Aggregation totals = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("bucket").is(bucket).and("day").is(day).and("rolled").is(true)),
                Aggregation.unwind("legs"),
                Aggregation.group("legs.accountId", "legs.kind")
                    .sum("legs.amountMinor").as("amountMinor")
                    .count().as("lines")
                    .max("brandId").as("brandId")
                    .first("currency").as("currency"));
        for (Document row : this.mongo.aggregate(totals, TransactionDocument.class, Document.class).getMappedResults()) {
            Document id = row.get("_id", Document.class);
            String accountId = id.getString("accountId");
            String kind = id.getString("kind");
            String brandId = Accounts.STORE.equals(Accounts.partyType(accountId)) ? row.getString("brandId") : null;
            String rowId = accountId + "|" + day + "|" + kind + "|" + bucket;
            this.mongo.upsert(Query.query(Criteria.where("accountId").is(accountId).and("_id").is(rowId)),
                    new Update().set("partyType", Accounts.partyType(accountId))
                        .set("brandId", brandId)
                        .set("day", day)
                        .set("kind", kind)
                        .set("bucket", bucket)
                        .set("amountMinor", number(row.get("amountMinor")))
                        .set("lines", number(row.get("lines")))
                        .set("currency", row.getString("currency")),
                    DailyDocument.class);
        }
    }

    /** An account's balance now: its roll-ups plus what is not rolled up yet. */
    public static long balance(MongoTemplate mongo, String accountId) {
        List<Document> daily = List.of(new Document("$match", new Document("accountId", accountId)),
                new Document("$group", new Document("_id", null).append("m", new Document("$sum", "$amountMinor"))));
        long sum = 0;
        for (Document row : mongo.getCollection(mongo.getCollectionName(DailyDocument.class)).aggregate(daily)) {
            sum += number(row.get("m"));
        }
        List<Document> fresh = List.of(
                new Document("$match", new Document("legs.accountId", accountId).append("rolled", false)),
                new Document("$unwind", "$legs"), new Document("$match", new Document("legs.accountId", accountId)),
                new Document("$group", new Document("_id", null).append("m", new Document("$sum", "$legs.amountMinor"))));
        for (Document row : mongo.getCollection(mongo.getCollectionName(TransactionDocument.class)).aggregate(fresh)) {
            sum += number(row.get("m"));
        }
        return sum;
    }

    static long number(Object value) {
        return (value instanceof Number n) ? n.longValue() : 0;
    }
}
