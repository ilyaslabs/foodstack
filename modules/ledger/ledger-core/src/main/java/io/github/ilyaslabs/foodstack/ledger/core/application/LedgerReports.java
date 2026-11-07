package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.ilyaslabs.foodstack.ledger.api.StoreBalance;
import io.github.ilyaslabs.foodstack.ledger.api.events.PayoutSettled;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreBalanceSettled;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Accounts;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Kinds;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Leg;
import io.github.ilyaslabs.foodstack.ledger.core.domain.Postings;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.AccountDocument;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.DailyDocument;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.SettlementDocument;
import io.github.ilyaslabs.foodstack.ledger.core.persistence.TransactionDocument;
import io.github.ilyaslabs.foodstack.platform.core.Buckets;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Balances, payout reports, statements and settlement (PRD FI-1, FI-3, FI-7, BB-8). Reports read
 * the daily roll-ups plus the few transactions not rolled up yet, so they are exact without summing
 * orders. Finance sees everything; a brand owner sees the brand's branches, a branch manager their
 * branches, and a rider their own account.
 */
public class LedgerReports {

    /** See every branch's and rider's balance and statement. */
    public static final String LEDGER_READ = "ledger:read";

    /** Settle balances. */
    public static final String LEDGER_SETTLE = "ledger:settle";

    /** See one's own branches' balances (brand and branch owners, not their staff). */
    public static final String LEDGER_STORE = "ledger:store";

    static final int MAX_DAYS = 366;

    private static final String OPENING = "OPEN";

    private static final String IN = "IN";

    private static final String AFTER = "AFTER";

    private final MongoTemplate mongo;

    private final TransactionTemplate transactions;

    private final OutboxWriter outbox;

    private final LedgerPosting posting;

    private final SettingsApi settings;

    private final Clock clock;

    private final CreditLimits creditLimits;

    public LedgerReports(MongoTemplate mongo, TransactionTemplate transactions, OutboxWriter outbox, LedgerPosting posting,
            SettingsApi settings, Clock clock) {
        this(mongo, transactions, outbox, posting, settings, clock, null);
    }

    /** @param creditLimits checks a branch's credit limit after it is settled, or {@code null} */
    public LedgerReports(MongoTemplate mongo, TransactionTemplate transactions, OutboxWriter outbox, LedgerPosting posting,
            SettingsApi settings, Clock clock, CreditLimits creditLimits) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.outbox = outbox;
        this.posting = posting;
        this.settings = settings;
        this.clock = clock;
        this.creditLimits = creditLimits;
    }

    // ---- payout reports -----------------------------------------------------------------------

    /**
     * Every branch's (or rider's) balance for a period, netted, with what moved by kind and who pays
     * whom. {@code brandId} limits it to a brand's branches, with the brand's totals (the roll-up).
     */
    public PayoutReport payouts(String partyType, String from, String to, String brandId) {
        String type = partyType(partyType);
        LocalDate first = date(from, "from");
        LocalDate last = date(to, "to");
        period(first, last);
        Criteria scope = scope(type, brandId);

        Map<String, Totals> byAccount = new LinkedHashMap<>();
        String daily = this.mongo.getCollectionName(DailyDocument.class);
        List<Document> rolled = List.of(new Document("$match", scope.getCriteriaObject()),
                new Document("$group", new Document("_id", new Document("a", "$accountId").append("k", "$kind")
                    .append("p", period("$day", first, last))).append("m", new Document("$sum", "$amountMinor"))));
        for (Document row : this.mongo.getCollection(daily).aggregate(rolled)) {
            add(byAccount, row);
        }
        for (Document row : this.mongo.getCollection(this.mongo.getCollectionName(TransactionDocument.class))
            .aggregate(unrolled(scope, first, last))) {
            add(byAccount, row);
        }

        Map<String, AccountDocument> accounts = accounts(byAccount.keySet());
        List<PayoutRow> rows = new ArrayList<>();
        for (Map.Entry<String, Totals> entry : byAccount.entrySet()) {
            Totals t = entry.getValue();
            long closing = t.opening + t.in.values().stream().mapToLong(Long::longValue).sum();
            long due = closing + t.settledAfter;
            if (t.opening == 0 && t.in.isEmpty() && due == 0 && closing == 0) {
                continue;
            }
            String accountId = entry.getKey();
            AccountDocument account = accounts.get(accountId);
            rows.add(new PayoutRow(accountId, type, Accounts.partyId(accountId),
                    (account != null && account.name() != null) ? account.name() : Accounts.partyId(accountId),
                    (account != null) ? account.brandId() : null, t.opening, ordered(t.in), closing, t.settledAfter, due,
                    PayoutRow.direction(due)));
        }
        rows.sort(Comparator.comparing((PayoutRow r) -> r.name().toLowerCase(Locale.ROOT)).thenComparing(PayoutRow::accountId));

        Map<String, Long> totalKinds = new TreeMap<>();
        long opening = 0;
        long closing = 0;
        long due = 0;
        long operatorPays = 0;
        long partiesPay = 0;
        for (PayoutRow row : rows) {
            row.kinds().forEach((k, v) -> totalKinds.merge(k, v, Long::sum));
            opening += row.openingMinor();
            closing += row.closingMinor();
            due += row.dueMinor();
            operatorPays += Math.max(0, row.dueMinor());
            partiesPay += Math.max(0, -row.dueMinor());
        }
        return new PayoutReport(type, first.toString(), last.toString(), brandId, currency(), List.copyOf(rows),
                ordered(totalKinds), opening, closing, due, operatorPays, partiesPay);
    }

    /** {@link #payouts} as CSV (PRD FI-3). */
    public String payoutsCsv(String partyType, String from, String to, String brandId) {
        return PayoutExports.csv(payouts(partyType, from, to, brandId));
    }

    /** {@link #payouts} as a PDF with the operator's name on top (PRD FI-3). */
    public byte[] payoutsPdf(String partyType, String from, String to, String brandId) {
        PayoutReport report = payouts(partyType, from, to, brandId);
        String name;
        try {
            name = this.settings.getBranding().name();
        }
        catch (RuntimeException ex) {
            name = null;
        }
        return PayoutExports.pdf(report, name);
    }

    /** What the operator earned and spent in a period, and the money on its way at the end of it. Finance only. */
    public OperatorSummary operator(String from, String to) {
        requireFinance();
        LocalDate first = date(from, "from");
        LocalDate last = date(to, "to");
        period(first, last);
        Map<String, Long> accounts = new TreeMap<>();
        Map<String, Long> held = new TreeMap<>();
        String daily = this.mongo.getCollectionName(DailyDocument.class);
        List<Document> pipeline = List.of(
                new Document("$match", new Document("partyType", new Document("$in", List.of(Accounts.OPERATOR, Accounts.CLEARING)))
                    .append("day", new Document("$lte", last.toString()))),
                new Document("$group", new Document("_id", new Document("a", "$accountId").append("k", "$kind")
                    .append("p", period("$day", first, last))).append("m", new Document("$sum", "$amountMinor"))));
        List<Document> rows = new ArrayList<>();
        this.mongo.getCollection(daily).aggregate(pipeline).into(rows);
        Criteria all = new Criteria().orOperator(Criteria.where("legs.accountId").regex("^" + Accounts.OPERATOR_PREFIX),
                Criteria.where("legs.accountId").regex("^clearing:"));
        this.mongo.getCollection(this.mongo.getCollectionName(TransactionDocument.class))
            .aggregate(unrolled(all, first, last))
            .into(rows);
        for (Document row : rows) {
            Document id = row.get("_id", Document.class);
            String accountId = id.getString("a");
            String period = id.getString("p");
            long amount = Rollups.number(row.get("m"));
            if (accountId.startsWith(Accounts.OPERATOR_PREFIX)) {
                if (IN.equals(period) && !Accounts.BANK.equals(accountId)) {
                    accounts.merge(accountId, amount, Long::sum);
                }
            }
            else if (!AFTER.equals(period)) {
                held.merge(accountId, amount, Long::sum);
            }
        }
        long net = accounts.values().stream().mapToLong(Long::longValue).sum();
        return new OperatorSummary(first.toString(), last.toString(), currency(), accounts, net, held);
    }

    // ---- statements and balances -------------------------------------------------------------

    /** An account's transactions, newest first; {@code before} is the previous page's {@code nextBefore}. */
    public StatementPage statement(String accountId, String from, String to, String before, int limit) {
        requireAccountAccess(accountId);
        int size = Math.max(1, Math.min(limit, 100));
        Criteria where = Criteria.where("legs.accountId").is(accountId);
        if (from != null && !from.isBlank()) {
            where = where.and("day").gte(date(from, "from").toString());
            if (to != null && !to.isBlank()) {
                where.lte(date(to, "to").toString());
            }
        }
        else if (to != null && !to.isBlank()) {
            where = where.and("day").lte(date(to, "to").toString());
        }
        if (before != null && !before.isBlank()) {
            // the marker is "<at>|<id>": lines at the same moment are told apart by id
            int bar = before.indexOf('|');
            Instant at = instant((bar > 0) ? before.substring(0, bar) : before);
            if (bar > 0) {
                String id = before.substring(bar + 1);
                where = where.orOperator(Criteria.where("at").lt(at), Criteria.where("at").is(at).and("_id").lt(id));
            }
            else {
                where = where.and("at").lt(at);
            }
        }
        Query query = Query.query(where).with(Sort.by(Sort.Order.desc("at"), Sort.Order.desc("_id"))).limit(size + 1);
        List<TransactionDocument> found = this.mongo.find(query, TransactionDocument.class);
        boolean more = found.size() > size;
        List<StatementLine> lines = new ArrayList<>();
        for (TransactionDocument t : found.subList(0, Math.min(size, found.size()))) {
            Map<String, Long> kinds = new TreeMap<>();
            long sum = 0;
            for (Leg leg : t.legs()) {
                if (leg.accountId().equals(accountId)) {
                    kinds.merge(leg.kind(), leg.amountMinor(), Long::sum);
                    sum += leg.amountMinor();
                }
            }
            lines.add(new StatementLine(t.id(), t.type(), t.orderId(), t.day(), t.at(), kinds, sum, t.note()));
        }
        AccountDocument account = this.mongo.findById(accountId, AccountDocument.class);
        StatementLine last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
        String nextBefore = (more && last != null) ? last.at() + "|" + last.transactionId() : null;
        return new StatementPage(accountId, (account != null) ? account.name() : Accounts.partyId(accountId),
                balance(accountId), currency(), List.copyOf(lines), nextBefore);
    }

    /** A branch's balance with the operator now (for the credit limit of direct collection). */
    public StoreBalance storeBalance(String storeId) {
        return new StoreBalance(storeId, balance(Accounts.store(storeId)), currency());
    }

    /** An account's balance now: its roll-ups plus what is not rolled up yet. */
    public long balance(String accountId) {
        return Rollups.balance(this.mongo, accountId);
    }

    // ---- settlement ---------------------------------------------------------------------------

    /**
     * Settles a branch's or rider's balance up to the end of {@code to} (a day already over), with
     * the bank or payment reference (PRD FI-3). What was due moves to the operator's bank account;
     * settling the same period again finds nothing due.
     */
    public SettlementView settle(String accountId, String to, String reference) {
        if (!CurrentUser.hasAuthority(LEDGER_SETTLE)) {
            throw new ModuleException.Forbidden("Only finance can settle balances", "LEDGER_ACCESS_DENIED");
        }
        String type = Accounts.partyType(accountId == null ? "" : accountId);
        if (!Accounts.STORE.equals(type) && !Accounts.RIDER.equals(type)) {
            throw new ModuleException.BadRequest("Only branch and rider balances are settled", "LEDGER_ACCOUNT_INVALID");
        }
        String ref = (reference != null) ? reference.strip() : "";
        if (ref.isEmpty() || ref.length() > 200) {
            throw new ModuleException.BadRequest("Enter the bank or payment reference (up to 200 characters)",
                    "SETTLEMENT_REFERENCE_REQUIRED");
        }
        LocalDate last = date(to, "to");
        LocalDate today = LocalDate.ofInstant(this.clock.instant(), this.posting.zone());
        if (!last.isBefore(today)) {
            throw new ModuleException.BadRequest("Settle up to yesterday at the latest; today is not over yet",
                    "SETTLEMENT_PERIOD_OPEN");
        }
        SettlementDocument latest = this.mongo.findOne(Query.query(Criteria.where("accountId").is(accountId))
            .with(Sort.by(Sort.Order.desc("periodTo"))).limit(1), SettlementDocument.class);
        if (latest != null && last.toString().compareTo(latest.periodTo()) < 0) {
            throw new ModuleException.Conflict("This balance is already settled up to " + latest.periodTo(),
                    "SETTLEMENT_PERIOD_SETTLED", Map.of("periodTo", latest.periodTo()));
        }
        long due = dueUpTo(accountId, last);
        if (due == 0) {
            throw new ModuleException.Conflict("Nothing is due for this period", "NOTHING_TO_SETTLE");
        }
        AccountDocument account = this.mongo.findById(accountId, AccountDocument.class);
        String partyId = Accounts.partyId(accountId);
        long earlier = this.mongo.count(Query.query(Criteria.where("accountId").is(accountId)), SettlementDocument.class);
        String id = accountId + "|" + last + "|" + earlier;
        Instant now = this.clock.instant();
        String currency = currency();
        String actor = CurrentUser.id();
        String brandId = (account != null) ? account.brandId() : null;
        long balanceAfter = balance(accountId) - due;
        SettlementDocument settlement = new SettlementDocument(id, accountId, type, partyId, brandId, last.toString(), due,
                currency, ref, actor, now);
        try {
            this.transactions.executeWithoutResult((status) -> {
                this.mongo.insert(settlement);
                String partition = Accounts.STORE.equals(type) ? partyId : accountId;
                List<Leg> legs = Postings.settlement(accountId, due);
                this.mongo.insert(new TransactionDocument("settlement:" + id, partition, Buckets.of(partition),
                        LedgerPosting.SETTLEMENT, Accounts.STORE.equals(type) ? partyId : null, brandId, null, legs, currency,
                        today.toString(), now, ref, false));
                this.outbox.append(new PayoutSettled(id, accountId, type, partyId, due, currency, last.toString(), ref,
                        (actor != null) ? "user:" + actor : "system"));
                if (Accounts.STORE.equals(type)) {
                    this.outbox.append(new StoreBalanceSettled(partyId, balanceAfter, currency));
                }
            });
        }
        catch (DuplicateKeyException ex) {
            throw new ModuleException.Conflict("Someone settled this balance just now", "SETTLEMENT_CHANGED");
        }
        if (Accounts.STORE.equals(type) && this.creditLimits != null) {
            this.creditLimits.check(partyId);
        }
        return view(settlement, account);
    }

    /** Settlements, newest first: one account's (its party may see them) or everyone's (finance). */
    public List<SettlementView> settlements(String accountId, int limit) {
        Query query;
        if (accountId != null && !accountId.isBlank()) {
            requireAccountAccess(accountId);
            query = Query.query(Criteria.where("accountId").is(accountId));
        }
        else {
            requireFinance();
            query = new Query();
        }
        query.with(Sort.by(Sort.Order.desc("settledAt"))).limit(Math.max(1, Math.min(limit, 200)));
        List<SettlementDocument> found = this.mongo.find(query, SettlementDocument.class);
        Set<String> ids = new HashSet<>();
        found.forEach((s) -> ids.add(s.accountId()));
        Map<String, AccountDocument> accounts = accounts(ids);
        return found.stream().map((s) -> view(s, accounts.get(s.accountId()))).toList();
    }

    /** What is due for a period: the balance at its end, less nothing settled after it. */
    long dueUpTo(String accountId, LocalDate last) {
        Document counted = new Document("$or", List.of(new Document("day", new Document("$lte", last.toString())),
                new Document("legs.kind", Kinds.SETTLEMENT)));
        List<Document> pipeline = List.of(new Document("$match", new Document("legs.accountId", accountId)),
                new Document("$unwind", "$legs"), new Document("$match", new Document("legs.accountId", accountId)),
                new Document("$match", counted),
                new Document("$group", new Document("_id", null).append("m", new Document("$sum", "$legs.amountMinor"))));
        long sum = 0;
        for (Document row : this.mongo.getCollection(this.mongo.getCollectionName(TransactionDocument.class))
            .aggregate(pipeline)) {
            sum += Rollups.number(row.get("m"));
        }
        return sum;
    }

    // ---- access -------------------------------------------------------------------------------

    /** Which roll-up rows the caller may read. */
    Criteria scope(String type, String brandId) {
        Criteria where = Criteria.where("partyType").is(type);
        if (CurrentUser.hasAuthority(LEDGER_READ)) {
            return (brandId != null && !brandId.isBlank()) ? where.and("brandId").is(brandId) : where;
        }
        if (!Accounts.STORE.equals(type)) {
            throw new ModuleException.Forbidden("Only finance sees rider balances", "LEDGER_ACCESS_DENIED");
        }
        if (!CurrentUser.hasAuthority(LEDGER_STORE)) {
            throw new ModuleException.Forbidden("Only owners see branch balances", "LEDGER_ACCESS_DENIED");
        }
        if (brandId != null && !brandId.isBlank()) {
            if (!CurrentUser.brandIds().contains(brandId)) {
                throw new ModuleException.Forbidden("You do not own this brand", "BRAND_ACCESS_DENIED");
            }
            return where.and("brandId").is(brandId);
        }
        Set<String> stores = CurrentUser.storeIds();
        Set<String> brands = CurrentUser.brandIds();
        if (stores.isEmpty() && brands.isEmpty()) {
            throw new ModuleException.Forbidden("You do not manage a branch", "LEDGER_ACCESS_DENIED");
        }
        List<Criteria> any = new ArrayList<>();
        if (!stores.isEmpty()) {
            any.add(Criteria.where("accountId").in(stores.stream().map(Accounts::store).toList()));
        }
        if (!brands.isEmpty()) {
            any.add(Criteria.where("brandId").in(brands));
        }
        return where.orOperator(any.toArray(Criteria[]::new));
    }

    void requireAccountAccess(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new ModuleException.BadRequest("Choose an account", "LEDGER_ACCOUNT_INVALID");
        }
        if (CurrentUser.hasAuthority(LEDGER_READ)) {
            return;
        }
        String type = Accounts.partyType(accountId);
        String partyId = Accounts.partyId(accountId);
        boolean own = (Accounts.STORE.equals(type) && CurrentUser.hasAuthority(LEDGER_STORE) && CurrentUser.managesStore(partyId))
                || (Accounts.RIDER.equals(type) && partyId.equals(CurrentUser.id()));
        if (!own) {
            throw new ModuleException.Forbidden("You cannot see this account", "LEDGER_ACCESS_DENIED");
        }
    }

    private static void requireFinance() {
        if (!CurrentUser.hasAuthority(LEDGER_READ)) {
            throw new ModuleException.Forbidden("Only finance sees the operator's accounts", "LEDGER_ACCESS_DENIED");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Transactions of the scope not rolled up yet, unwound and grouped like the roll-ups. */
    private static List<Document> unrolled(Criteria scope, LocalDate first, LocalDate last) {
        Document legScope = legScope(scope.getCriteriaObject());
        return List.of(new Document("$match", new Document("rolled", false)), new Document("$unwind", "$legs"),
                new Document("$match", legScope),
                new Document("$group", new Document("_id", new Document("a", "$legs.accountId").append("k", "$legs.kind")
                    .append("p", period("$day", first, last))).append("m", new Document("$sum", "$legs.amountMinor"))));
    }

    /**
     * The roll-up filter rewritten for unwound transaction lines: {@code accountId} becomes
     * {@code legs.accountId}, {@code partyType} an account prefix, {@code brandId} stays.
     */
    static Document legScope(Document criteria) {
        Document out = new Document();
        for (Map.Entry<String, Object> entry : criteria.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "accountId" -> out.append("legs.accountId", value);
                case "legs.accountId" -> out.append("legs.accountId", value);
                case "partyType" -> out.append("legs.accountId", new Document("$regex", "^" + prefix(String.valueOf(value))));
                case "$or", "$and" -> {
                    List<Document> parts = new ArrayList<>();
                    for (Object part : (List<?>) value) {
                        parts.add(legScope((Document) part));
                    }
                    out.append(key, parts);
                }
                default -> out.append(key, value);
            }
        }
        return out;
    }

    private static String prefix(String partyType) {
        return switch (partyType) {
            case Accounts.STORE -> Accounts.STORE_PREFIX;
            case Accounts.RIDER -> Accounts.RIDER_PREFIX;
            case Accounts.OPERATOR -> Accounts.OPERATOR_PREFIX;
            default -> "clearing:";
        };
    }

    /** {@code OPEN} before the period, {@code IN} inside it, {@code AFTER} after it. */
    private static Document period(String dayField, LocalDate first, LocalDate last) {
        return new Document("$switch", new Document("branches", List.of(
                new Document("case", new Document("$lt", List.of(dayField, first.toString()))).append("then", OPENING),
                new Document("case", new Document("$lte", List.of(dayField, last.toString()))).append("then", IN)))
            .append("default", AFTER));
    }

    private static void add(Map<String, Totals> byAccount, Document row) {
        Document id = row.get("_id", Document.class);
        String accountId = id.getString("a");
        String kind = id.getString("k");
        String period = id.getString("p");
        long amount = Rollups.number(row.get("m"));
        Totals totals = byAccount.computeIfAbsent(accountId, (k) -> new Totals());
        switch (period) {
            case OPENING -> totals.opening += amount;
            case IN -> totals.in.merge(kind, amount, Long::sum);
            default -> {
                if (Kinds.SETTLEMENT.equals(kind)) {
                    totals.settledAfter += amount;
                }
            }
        }
    }

    /** Kinds in report order, zero totals left out. */
    private static Map<String, Long> ordered(Map<String, Long> kinds) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String kind : Kinds.REPORTED) {
            Long value = kinds.get(kind);
            if (value != null && value != 0) {
                out.put(kind, value);
            }
        }
        kinds.forEach((k, v) -> {
            if (!out.containsKey(k) && v != 0) {
                out.put(k, v);
            }
        });
        return out;
    }

    private Map<String, AccountDocument> accounts(Set<String> ids) {
        Map<String, AccountDocument> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (AccountDocument a : this.mongo.find(Query.query(Criteria.where("_id").in(ids)), AccountDocument.class)) {
            out.put(a.id(), a);
        }
        return out;
    }

    private static SettlementView view(SettlementDocument s, AccountDocument account) {
        return new SettlementView(s.id(), s.accountId(), s.partyType(), s.partyId(),
                (account != null && account.name() != null) ? account.name() : s.partyId(), s.periodTo(), s.amountMinor(),
                s.currency(), s.reference(), s.settledBy(), s.settledAt());
    }

    private String currency() {
        return this.settings.getLocale().currency();
    }

    private static String partyType(String value) {
        String type = (value == null || value.isBlank()) ? Accounts.STORE : value.toUpperCase(Locale.ROOT);
        if (!Accounts.STORE.equals(type) && !Accounts.RIDER.equals(type)) {
            throw new ModuleException.BadRequest("Choose branches or riders", "LEDGER_PARTY_INVALID");
        }
        return type;
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ModuleException.BadRequest("Enter " + field + " as a date such as 2026-11-01", "LEDGER_DATE_INVALID",
                    Map.of("field", field));
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ex) {
            throw new ModuleException.BadRequest("Page marker is not valid", "LEDGER_PAGE_INVALID");
        }
    }

    private static void period(LocalDate first, LocalDate last) {
        if (last.isBefore(first)) {
            throw new ModuleException.BadRequest("The period ends before it starts", "LEDGER_PERIOD_INVALID");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw new ModuleException.BadRequest("Choose a period of a year or less", "LEDGER_PERIOD_INVALID");
        }
    }

    /** One account's sums while a report is built. */
    private static final class Totals {

        long opening;

        final Map<String, Long> in = new TreeMap<>();

        long settledAfter;
    }
}
