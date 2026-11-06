package io.github.ilyaslabs.foodstack.dispatch.core.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus;
import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCancelled;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryCompleted;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryHandedBack;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DeliveryPickedUp;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DispatchAlert;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderOffered;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderUnassigned;
import io.github.ilyaslabs.foodstack.dispatch.core.persistence.ActiveJobDocument;
import io.github.ilyaslabs.foodstack.dispatch.core.persistence.JobDocument;
import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.RouteRequest;
import io.github.ilyaslabs.foodstack.order.api.OrderDelivery;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.rider.api.RiderApi;
import io.github.ilyaslabs.foodstack.rider.api.RiderCandidate;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderSearch;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.RiderView;
import io.github.ilyaslabs.foodstack.settings.api.DeliveryView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivery jobs (PRD 4.6, TDD 5.6). Accepted delivery orders become jobs. Operator jobs search for
 * a rider from shortly before the food is ready: one offer at a time to the best free rider nearby,
 * a widening radius when nobody is near, and an alert to support while the search goes on.
 * Self-delivery jobs wait for the store to pick one of its riders, or are assigned at once when the
 * store chose one while accepting. Riders then report reaching the store, pickup and delivery with
 * proof. Support can reassign a job or hand an operator delivery back to the store. Every change is
 * conditional on the job's revision and writes its events through the outbox in one transaction.
 */
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    public static final String MANAGE = "dispatch:manage";

    public static final String RIDER_PORTAL = "rider:portal";

    /** How many times the search radius grows by half when nobody is near. */
    static final int MAX_WIDENINGS = 2;

    /** How long a round runs at one radius before it grows. */
    static final Duration ROUND = Duration.ofSeconds(45);

    /** Declines and lapsed offers are forgotten after this, so those riders are asked again. */
    static final Duration RETRY_TRIED_AFTER = Duration.ofMinutes(3);

    private static final Set<DeliveryStatus> BEFORE_PICKUP = EnumSet.of(DeliveryStatus.ASSIGNED, DeliveryStatus.AT_STORE);

    private static final Set<DeliveryStatus> OPEN = EnumSet.of(DeliveryStatus.WAITING, DeliveryStatus.SEARCHING,
            DeliveryStatus.OFFERED, DeliveryStatus.ASSIGNED, DeliveryStatus.AT_STORE, DeliveryStatus.PICKED_UP);

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final DispatchLive live;

    private final RiderApi riders;

    private final SettingsApi settings;

    private final GeoApi geo;

    private final ServiceCalls serviceCalls;

    private final RateLimiter rateLimiter;

    private final PiiCipher pii;

    private final Clock clock;

    public DispatchService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, DispatchLive live,
            RiderApi riders, SettingsApi settings, GeoApi geo, ServiceCalls serviceCalls, RateLimiter rateLimiter, PiiCipher pii,
            Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.live = live;
        this.riders = riders;
        this.settings = settings;
        this.geo = geo;
        this.serviceCalls = serviceCalls;
        this.rateLimiter = rateLimiter;
        this.pii = pii;
        this.clock = clock;
    }

    // ---- from order events ---------------------------------------------------------------------

    /** An accepted delivery order becomes a job (once; a replayed event finds it there). */
    public void orderAccepted(OrderSummary order, OrderDelivery delivery, long readyByEpoch, String actor) {
        if (delivery == null || this.mongo.findById(order.orderId(), JobDocument.class) != null) {
            return;
        }
        Instant now = this.clock.instant();
        DeliveryView rules = rules();
        Instant readyBy = Instant.ofEpochSecond(readyByEpoch);
        Instant searchFrom = readyBy.minus(Duration.ofMinutes(rules.searchLeadMinutes()));
        if (searchFrom.isBefore(now)) {
            searchFrom = now;
        }
        long distance = roadDistance(delivery.pickup(), delivery.dropoff());
        boolean operator = delivery.mode() == DeliveryMode.OPERATOR;
        JobDocument job = new JobDocument(order.orderId(), order.number(), order.customerId(), delivery.customerName(),
                delivery.contactPhoneCipher(), order.storeId(), order.storeName(), order.storeOwnerIds(), delivery.mode().name(),
                (operator ? DeliveryStatus.SEARCHING : DeliveryStatus.WAITING).name(), null, null, null, null,
                delivery.pickup().lat(), delivery.pickup().lng(), delivery.pickupAddress(), delivery.dropoff().lat(),
                delivery.dropoff().lng(), delivery.dropoffAddress(), delivery.instructions(), delivery.deliveryChargeMinor(),
                delivery.tipMinor(), delivery.cashToCollectMinor(), delivery.currency(), hash(decrypt(delivery.deliveryCodeCipher())),
                order.language(), readyBy, searchFrom, null, rules.searchRadiusMeters(), 0, null, null, List.of(), null, null,
                null, null, null, null, null, null, 0, 0, distance, delivery.riderPayBasisMinor(), now, now, 0,
                delivery.cityId());
        try {
            this.transaction.executeWithoutResult((tx) -> {
                this.mongo.insert(job);
                this.mongo.insert(active(job));
            });
        }
        catch (DuplicateKeyException ex) {
            return;
        }
        this.live.publish(job.id(), job.pickupLat(), job.pickupLng(), job.status(), null, "job");
        if (!operator && delivery.storeRiderId() != null) {
            try {
                assignStoreRiderAs(job.id(), delivery.storeRiderId(), actor);
            }
            catch (ModuleException ex) {
                log.info("Store rider {} not assigned to order {}: {}", delivery.storeRiderId(), job.id(), ex.getMessage());
            }
        }
    }

    /** The food is ready: an operator search that was waiting for the lead time starts now. */
    public void orderReady(String orderId) {
        Instant now = this.clock.instant();
        this.mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId).and("status").is(DeliveryStatus.SEARCHING.name())
            .and("searchFrom").gt(now)), new Update().set("searchFrom", now), ActiveJobDocument.class);
        this.mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId).and("status").is(DeliveryStatus.SEARCHING.name())
            .and("searchFrom").gt(now)), new Update().set("searchFrom", now), JobDocument.class);
    }

    /** The order ended before delivery: the job is cancelled and its rider freed. */
    public void orderClosed(String orderId, String reason) {
        JobDocument job = this.mongo.findById(orderId, JobDocument.class);
        if (job == null || status(job).isFinal()) {
            return;
        }
        change(job, OPEN, DeliveryStatus.CANCELLED, (j, now) -> new Change(
                new Update().set("closedAt", now).set("closeReason", reason).unset("offerRiderId").unset("offerExpiresAt"),
                (after) -> List.of(new DeliveryCancelled(summary(after), j.riderId(), reason))));
        this.live.releaseRider(job.offerRiderId(), orderId);
    }

    /** The store or support marked a step on the order itself (self delivery without the rider app). */
    public void orderPickedUpElsewhere(String orderId, String actor) {
        JobDocument job = this.mongo.findById(orderId, JobDocument.class);
        if (job == null || status(job).isFinal() || status(job) == DeliveryStatus.PICKED_UP || isRiderActor(actor)) {
            return;
        }
        change(job, OPEN, DeliveryStatus.PICKED_UP, (j, now) -> new Change(new Update().set("pickedUpAt", now)
            .unset("offerRiderId").unset("offerExpiresAt"),
                (after) -> (j.riderId() != null) ? List.of(new DeliveryPickedUp(summary(after), actor)) : List.of()));
        this.live.releaseRider(job.offerRiderId(), orderId);
    }

    /** The store or support marked the order delivered: the job closes, and a rider on it is paid. */
    public void orderDeliveredElsewhere(String orderId, String actor) {
        JobDocument job = this.mongo.findById(orderId, JobDocument.class);
        if (job == null || status(job).isFinal() || isRiderActor(actor)) {
            return;
        }
        complete(job, actor, "SUPPORT", null, job.cashToCollectMinor());
        this.live.releaseRider(job.offerRiderId(), orderId);
    }

    // ---- matching ------------------------------------------------------------------------------

    /**
     * One round of the matcher (each replica over its work buckets, TDD 7.5): lapsed offers go back to searching,
     * then each job due for a rider gets an offer, a wider radius or an alert. Returns offers made.
     */
    public int match() {
        return match(null);
    }

    /** One round over the jobs of the given work buckets ({@code null} = every bucket). */
    public int match(Set<Integer> buckets) {
        Instant now = this.clock.instant();
        expireOffers(now, buckets);
        List<ActiveJobDocument> due = this.mongo.find(Query.query(inBuckets(Criteria.where("status")
            .is(DeliveryStatus.SEARCHING.name())
            .and("searchFrom")
            .lte(now), buckets)).with(Sort.by("searchFrom")).limit(100), ActiveJobDocument.class);
        if (due.isEmpty()) {
            return 0;
        }
        DeliveryView rules = rules();
        Set<String> offeredThisRound = new HashSet<>();
        int offers = 0;
        for (ActiveJobDocument active : due) {
            JobDocument job = this.mongo.findById(active.id(), JobDocument.class);
            if (job == null || status(job) != DeliveryStatus.SEARCHING) {
                continue;
            }
            try {
                if (offer(job, rules, offeredThisRound, now)) {
                    offers++;
                }
            }
            catch (RuntimeException ex) {
                log.warn("Matching order {} failed: {}", job.id(), ex.getMessage());
            }
        }
        return offers;
    }

    private boolean offer(JobDocument job, DeliveryView rules, Set<String> offeredThisRound, Instant now) {
        Instant started = (job.searchStartedAt() != null) ? job.searchStartedAt() : now;
        List<String> tried = job.triedRiderIds();
        if (!tried.isEmpty() && job.updatedAt().plus(RETRY_TRIED_AFTER).isBefore(now)) {
            tried = List.of();
        }
        Set<String> exclude = new HashSet<>(tried);
        exclude.addAll(offeredThisRound);
        List<RiderCandidate> candidates = this.serviceCalls.call(() -> this.riders.findAvailable(
                new RiderSearch(job.pickupLat(), job.pickupLng(), job.radiusMeters(), 5, exclude, job.cityId())));
        List<RiderCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingLong(DispatchService::score));
        long earning = earningFor(job, RiderKind.OPERATOR, rules);
        for (RiderCandidate candidate : ranked) {
            Instant expires = now.plusSeconds(rules.offerTimeoutSeconds());
            if (!this.live.holdRider(candidate.riderId(), job.id(), Duration.ofSeconds(rules.offerTimeoutSeconds() + 5L))) {
                continue;
            }
            List<String> keptTried = tried;
            JobDocument after = change(job, EnumSet.of(DeliveryStatus.SEARCHING), DeliveryStatus.OFFERED, (j, at) -> new Change(
                    new Update().set("offerRiderId", candidate.riderId()).set("offerExpiresAt", expires)
                        .set("searchStartedAt", started).set("triedRiderIds", keptTried).set("earningMinor", earning),
                    (a) -> List.of(new RiderOffered(summary(a), candidate.riderId(), expires.getEpochSecond(), earning,
                            j.currency()))));
            if (after == null) {
                this.live.releaseRider(candidate.riderId(), job.id());
                return false;
            }
            offeredThisRound.add(candidate.riderId());
            this.live.publish(job.id(), job.pickupLat(), job.pickupLng(), DeliveryStatus.OFFERED.name(), candidate.riderId(), "offer");
            return true;
        }
        // nobody free nearby: widen after a round, alert support once, keep searching
        Update update = new Update().set("searchStartedAt", started).set("triedRiderIds", tried);
        boolean widen = job.widenings() < MAX_WIDENINGS
                && Duration.between(started, now).compareTo(ROUND.multipliedBy(job.widenings() + 1L)) >= 0;
        if (widen) {
            update.set("radiusMeters", (int) Math.round(job.radiusMeters() * 1.5)).inc("widenings", 1);
        }
        long searching = Duration.between(started, now).toSeconds();
        boolean alert = job.alertedAt() == null && searching >= rules.noRiderAlertSeconds();
        if (alert) {
            update.set("alertedAt", now);
        }
        change(job, EnumSet.of(DeliveryStatus.SEARCHING), DeliveryStatus.SEARCHING, (j, at) -> new Change(update,
                (a) -> alert ? List.of(new DispatchAlert(summary(a), "NO_RIDER", searching)) : List.of()));
        return false;
    }

    /** Lower is better: distance, less a bonus for waiting long, plus a penalty for declining often. */
    static long score(RiderCandidate c) {
        return c.distanceMeters() - Math.min(c.idleSeconds(), 1_800) + (100L - c.acceptancePercent()) * 20;
    }

    /** Limits a sweep to work buckets; rows from before buckets existed have none and any replica takes them. */
    private static Criteria inBuckets(Criteria criteria, Set<Integer> buckets) {
        if (buckets == null) {
            return criteria;
        }
        return criteria.orOperator(Criteria.where("bucket").in(buckets), Criteria.where("bucket").exists(false));
    }

    private void expireOffers(Instant now, Set<Integer> buckets) {
        List<ActiveJobDocument> lapsed = this.mongo.find(Query.query(inBuckets(Criteria.where("status")
            .is(DeliveryStatus.OFFERED.name())
            .and("offerExpiresAt")
            .lt(now), buckets)).limit(200), ActiveJobDocument.class);
        for (ActiveJobDocument active : lapsed) {
            JobDocument job = this.mongo.findById(active.id(), JobDocument.class);
            if (job == null || status(job) != DeliveryStatus.OFFERED) {
                continue;
            }
            backToSearch(job, job.offerRiderId(), "offer-expired");
        }
    }

    private void backToSearch(JobDocument job, String riderId, String kind) {
        List<String> tried = new ArrayList<>(job.triedRiderIds());
        if (riderId != null && !tried.contains(riderId)) {
            tried.add(riderId);
        }
        JobDocument after = change(job, EnumSet.of(DeliveryStatus.OFFERED), DeliveryStatus.SEARCHING, (j, now) -> new Change(
                new Update().set("triedRiderIds", tried).unset("offerRiderId").unset("offerExpiresAt"), (a) -> List.of()));
        if (after != null) {
            this.live.releaseRider(riderId, job.id());
            this.live.publish(job.id(), job.pickupLat(), job.pickupLng(), DeliveryStatus.SEARCHING.name(), riderId, kind);
        }
    }

    // ---- the rider -----------------------------------------------------------------------------

    /** The rider's open offer and current jobs (PRD DR-4). */
    public List<JobDetails> myJobs() {
        String me = requireRider();
        List<ActiveJobDocument> mine = this.mongo.find(Query.query(new Criteria().orOperator(Criteria.where("riderId").is(me),
                Criteria.where("offerRiderId").is(me))).limit(20), ActiveJobDocument.class);
        List<JobDetails> jobs = new ArrayList<>();
        for (ActiveJobDocument active : mine) {
            JobDocument job = this.mongo.findById(active.id(), JobDocument.class);
            if (job != null && (me.equals(job.riderId()) || me.equals(job.offerRiderId()))) {
                jobs.add(details(job, me.equals(job.riderId())));
            }
        }
        jobs.sort(Comparator.comparing(JobDetails::status));
        return jobs;
    }

    public JobDetails accept(String orderId) {
        String me = requireRider();
        JobDocument job = require(orderId);
        Instant now = this.clock.instant();
        if (status(job) != DeliveryStatus.OFFERED || !me.equals(job.offerRiderId())) {
            throw new ModuleException.Conflict("This offer is no longer open", "OFFER_GONE");
        }
        if (job.offerExpiresAt() != null && job.offerExpiresAt().isBefore(now)) {
            backToSearch(job, me, "offer-expired");
            throw new ModuleException.Conflict("This offer has lapsed", "OFFER_GONE");
        }
        RiderView rider = this.serviceCalls.call(() -> this.riders.getRider(me));
        JobDocument after = change(job, EnumSet.of(DeliveryStatus.OFFERED), DeliveryStatus.ASSIGNED, (j, at) -> new Change(
                assignment(rider, at).unset("offerRiderId").unset("offerExpiresAt").set("triedRiderIds", List.of()),
                (a) -> List.of(new RiderAssigned(summary(a), me, rider.name(), rider.vehicle().name(), "rider:" + me))));
        if (after == null) {
            throw new ModuleException.Conflict("This offer is no longer open", "OFFER_GONE");
        }
        this.live.releaseRider(me, orderId);
        return details(after, true);
    }

    public void decline(String orderId) {
        String me = requireRider();
        JobDocument job = require(orderId);
        if (status(job) == DeliveryStatus.OFFERED && me.equals(job.offerRiderId())) {
            backToSearch(job, me, "declined");
        }
    }

    public JobDetails arrived(String orderId) {
        JobDocument job = requireMine(orderId);
        return details(changeOrFail(job, EnumSet.of(DeliveryStatus.ASSIGNED), DeliveryStatus.AT_STORE,
                (j, now) -> new Change(new Update().set("atStoreAt", now), (a) -> List.of())), true);
    }

    public JobDetails pickedUp(String orderId) {
        JobDocument job = requireMine(orderId);
        String actor = "rider:" + job.riderId();
        return details(changeOrFail(job, BEFORE_PICKUP, DeliveryStatus.PICKED_UP, (j, now) -> new Change(
                new Update().set("pickedUpAt", now), (a) -> List.of(new DeliveryPickedUp(summary(a), actor)))), true);
    }

    /**
     * The rider hands the food over (PRD DR-6): the customer's code when the order needs one, cash
     * for a cash order. Support may finish a job for the rider without the code.
     */
    public JobDetails delivered(String orderId, Delivered proof) {
        JobDocument job = require(orderId);
        boolean support = CurrentUser.hasAuthority(MANAGE);
        String me = CurrentUser.id();
        if (!support && (me == null || !me.equals(job.riderId()))) {
            throw notFound();
        }
        DeliveryStatus status = status(job);
        if (status != DeliveryStatus.PICKED_UP && !BEFORE_PICKUP.contains(status)) {
            throw new ModuleException.Conflict("This delivery is not on its way", "DELIVERY_STATUS_CHANGED");
        }
        String kind = "NONE";
        if (!support && job.codeHash() != null) {
            this.rateLimiter.check("dispatch", "code", orderId, 5, Duration.ofMinutes(10));
            String code = (proof != null && proof.code() != null) ? proof.code().strip() : "";
            if (code.isEmpty()) {
                throw new ModuleException.BadRequest("Ask the customer for their delivery code", "DELIVERY_CODE_REQUIRED");
            }
            if (!MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.US_ASCII),
                    job.codeHash().getBytes(StandardCharsets.US_ASCII))) {
                throw new ModuleException.BadRequest("That code is not right", "DELIVERY_CODE_WRONG");
            }
            kind = "CODE";
        }
        else if (support) {
            kind = "SUPPORT";
        }
        String photo = (proof != null && proof.photoUrl() != null && !proof.photoUrl().isBlank()) ? proof.photoUrl().strip() : null;
        if ("NONE".equals(kind) && photo != null) {
            kind = "PHOTO";
        }
        long cash = (proof != null && proof.cashCollectedMinor() != null) ? proof.cashCollectedMinor() : job.cashToCollectMinor();
        if (cash < 0 || cash > job.cashToCollectMinor() * 2 + 1_000_000) {
            throw new ModuleException.BadRequest("The cash amount is not valid", "CASH_INVALID");
        }
        String actor = support ? "support:" + me : "rider:" + me;
        JobDocument after = complete(job, actor, kind, photo, cash);
        if (after == null) {
            throw changed();
        }
        return details(after, true);
    }

    /** The rider gives the job up before pickup; it goes back to searching (or to the store). */
    public void unassign(String orderId, String reason) {
        JobDocument job = requireMine(orderId);
        release(job, requireReason(reason), "rider:" + job.riderId());
    }

    // ---- store ---------------------------------------------------------------------------------

    /** The store gives a self-delivery job to one of its riders (PRD OF-8), or swaps the rider before pickup. */
    public JobDetails assignStoreRider(String orderId, String riderId) {
        JobDocument job = require(orderId);
        CurrentUser.requireStoreAccess(job.storeId(), MANAGE);
        return details(assignStoreRiderAs(orderId, riderId, actor()), true);
    }

    private JobDocument assignStoreRiderAs(String orderId, String riderId, String actor) {
        JobDocument job = require(orderId);
        if (!DeliveryMode.SELF.name().equals(job.mode())) {
            throw new ModuleException.Conflict("The operator's riders deliver this order", "OPERATOR_DELIVERY");
        }
        RiderView rider = this.serviceCalls.call(() -> this.riders.getRider(riderId));
        // a store rider rides for every branch of the brand (PRD BB-12); the job stays the branch's
        if (rider.kind() != RiderKind.STORE || !CurrentUser.sameBrand(job.storeId(), rider.storeId())
                || rider.status() != RiderStatus.ACTIVE) {
            throw new ModuleException.BadRequest("Choose one of this branch's active riders", "STORE_RIDER_INVALID");
        }
        if (riderId.equals(job.riderId())) {
            return job;
        }
        if (job.riderId() != null) {
            job = release(job, "Reassigned by the store", actor);
        }
        JobDocument current = job;
        return changeOrFail(current, EnumSet.of(DeliveryStatus.WAITING), DeliveryStatus.ASSIGNED, (j, now) -> new Change(
                assignment(rider, now),
                (a) -> List.of(new RiderAssigned(summary(a), riderId, rider.name(), rider.vehicle().name(), actor))));
    }

    // ---- support -------------------------------------------------------------------------------

    /** Open jobs for support's dispatch board (PRD AD-9), oldest first. */
    public List<JobDetails> open(DeliveryStatus status) {
        requireSupport();
        Criteria criteria = (status != null) ? Criteria.where("status").is(status.name()) : new Criteria();
        List<ActiveJobDocument> rows = this.mongo.find(Query.query(criteria).with(Sort.by("createdAt")).limit(500),
                ActiveJobDocument.class);
        List<JobDetails> jobs = new ArrayList<>();
        for (ActiveJobDocument row : rows) {
            JobDocument job = this.mongo.findById(row.id(), JobDocument.class);
            if (job != null) {
                jobs.add(details(job, true));
            }
        }
        return jobs;
    }

    /** Takes the rider off a job before pickup and, if named, gives it to another rider at once. */
    public JobDetails reassign(String orderId, String riderId, String reason) {
        requireSupport();
        String why = requireReason(reason);
        String actor = "support:" + CurrentUser.id();
        JobDocument job = require(orderId);
        if (status(job) == DeliveryStatus.OFFERED) {
            backToSearch(job, job.offerRiderId(), "reassigned");
            job = require(orderId);
        }
        if (job.riderId() != null) {
            job = release(job, why, actor);
        }
        if (riderId == null || riderId.isBlank()) {
            return details(job, true);
        }
        RiderView rider = this.serviceCalls.call(() -> this.riders.getRider(riderId));
        boolean fits = DeliveryMode.OPERATOR.name().equals(job.mode()) ? rider.kind() == RiderKind.OPERATOR
                : rider.kind() == RiderKind.STORE && CurrentUser.sameBrand(job.storeId(), rider.storeId());
        if (!fits || rider.status() != RiderStatus.ACTIVE) {
            throw new ModuleException.BadRequest("This rider cannot take this delivery", "RIDER_NOT_SUITABLE");
        }
        long earning = earningFor(job, rider.kind(), rules());
        return details(changeOrFail(job, EnumSet.of(DeliveryStatus.SEARCHING, DeliveryStatus.WAITING), DeliveryStatus.ASSIGNED,
                (j, now) -> new Change(assignment(rider, now).set("earningMinor", earning),
                        (a) -> List.of(new RiderAssigned(summary(a), riderId, rider.name(), rider.vehicle().name(), actor)))),
                true);
    }

    /**
     * Gives an operator delivery back to the store to deliver itself (PRD OF-8), when no rider can be
     * found or the rider cannot go on. Not after pickup.
     */
    public JobDetails handBack(String orderId, String reason) {
        requireSupport();
        String why = requireReason(reason);
        String actor = "support:" + CurrentUser.id();
        JobDocument job = require(orderId);
        if (!DeliveryMode.OPERATOR.name().equals(job.mode())) {
            throw new ModuleException.Conflict("The store delivers this order already", "NOT_OPERATOR_DELIVERY");
        }
        String offered = job.offerRiderId();
        JobDocument after = changeOrFail(job, EnumSet.of(DeliveryStatus.SEARCHING, DeliveryStatus.OFFERED,
                DeliveryStatus.ASSIGNED, DeliveryStatus.AT_STORE), DeliveryStatus.WAITING, (j, now) -> new Change(
                        new Update().set("mode", DeliveryMode.SELF.name()).set("closeReason", why).unset("riderId")
                            .unset("riderName").unset("riderKind").unset("vehicle").unset("offerRiderId")
                            .unset("offerExpiresAt").unset("assignedAt").unset("atStoreAt").set("earningMinor", 0),
                        (a) -> List.of(new DeliveryHandedBack(summary(a), j.riderId(), actor, why))));
        this.live.releaseRider(offered, orderId);
        return details(after, true);
    }

    // ---- reading -------------------------------------------------------------------------------

    /** A job for the rider on it, its store or support. */
    public JobDetails get(String orderId) {
        JobDocument job = require(orderId);
        String me = CurrentUser.id();
        boolean rider = me != null && me.equals(job.riderId());
        boolean offered = me != null && me.equals(job.offerRiderId());
        if (rider || offered || CurrentUser.hasAuthority(MANAGE) || CurrentUser.managesStore(job.storeId())) {
            return details(job, !offered || rider);
        }
        throw notFound();
    }

    public void requireBoard() {
        requireSupport();
    }

    public String requireRider() {
        String me = CurrentUser.id();
        if (me == null || !CurrentUser.hasAuthority(RIDER_PORTAL)) {
            throw new ModuleException.Forbidden("Only riders can do this", "RIDER_ONLY");
        }
        return me;
    }

    JobDocument require(String orderId) {
        JobDocument job = (orderId != null) ? this.mongo.findById(orderId, JobDocument.class) : null;
        if (job == null) {
            throw notFound();
        }
        return job;
    }

    JobDetails details(JobDocument j, boolean showContact) {
        String phone = null;
        if (showContact && j.contactPhoneCipher() != null && !status(j).isFinal()) {
            try {
                phone = this.pii.decrypt(j.contactPhoneCipher());
            }
            catch (RuntimeException ex) {
                phone = null;
            }
        }
        return new JobDetails(j.id(), j.orderNumber(), DeliveryMode.valueOf(j.mode()), status(j), j.storeId(), j.storeName(),
                j.pickupLat(), j.pickupLng(), j.pickupAddress(), j.customerName(), phone, j.dropoffLat(), j.dropoffLng(),
                showContact ? j.dropoffAddress() : null, showContact ? j.instructions() : null, j.riderId(), j.riderName(),
                (j.offerExpiresAt() != null) ? j.offerExpiresAt().getEpochSecond() : null, j.earningMinor(), j.tipMinor(),
                j.cashToCollectMinor(), j.currency(), j.codeHash() != null, j.distanceMeters(),
                (j.readyBy() != null) ? j.readyBy().getEpochSecond() : null,
                (j.searchStartedAt() != null) ? j.searchStartedAt().getEpochSecond() : null, j.closeReason(), j.revision());
    }

    static DeliverySummary summary(JobDocument j) {
        return new DeliverySummary(j.id(), j.orderNumber(), j.customerId(), j.storeId(), j.storeName(),
                (j.storeOwnerIds() != null) ? Set.copyOf(j.storeOwnerIds()) : Set.of(), DeliveryMode.valueOf(j.mode()), status(j),
                j.riderId(), j.riderName(), new GeoPoint(j.pickupLat(), j.pickupLng()), new GeoPoint(j.dropoffLat(), j.dropoffLng()),
                j.language());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private JobDocument release(JobDocument job, String reason, String actor) {
        if (!BEFORE_PICKUP.contains(status(job))) {
            throw new ModuleException.Conflict("The rider already has the food", "DELIVERY_STATUS_CHANGED");
        }
        boolean operator = DeliveryMode.OPERATOR.name().equals(job.mode());
        String riderId = job.riderId();
        List<String> tried = new ArrayList<>(job.triedRiderIds());
        if (operator && riderId != null && !tried.contains(riderId)) {
            tried.add(riderId);
        }
        Instant now = this.clock.instant();
        return changeOrFail(job, BEFORE_PICKUP, operator ? DeliveryStatus.SEARCHING : DeliveryStatus.WAITING, (j, at) -> new Change(
                new Update().unset("riderId").unset("riderName").unset("riderKind").unset("vehicle").unset("assignedAt")
                    .unset("atStoreAt").set("triedRiderIds", tried).set("searchFrom", now),
                (a) -> List.of(new RiderUnassigned(summary(a), riderId, reason, actor))));
    }

    private JobDocument complete(JobDocument job, String actor, String proof, String photo, long cash) {
        String riderKind = job.riderKind();
        long earning = (riderKind != null) ? earningFor(job, RiderKind.valueOf(riderKind), rules()) : 0;
        Set<DeliveryStatus> from = EnumSet.copyOf(OPEN);
        return change(job, from, DeliveryStatus.DELIVERED, (j, now) -> new Change(new Update().set("closedAt", now)
            .set("proof", proof).set("proofPhotoUrl", photo).set("cashCollectedMinor", cash).set("earningMinor", earning)
            .set("pickedUpAt", (j.pickedUpAt() != null) ? j.pickedUpAt() : now).unset("offerRiderId").unset("offerExpiresAt"),
                (a) -> List.of(new DeliveryCompleted(summary(a), actor, riderKind, j.deliveryChargeMinor(), earning,
                        (j.riderId() != null) ? j.tipMinor() : 0, cash, j.currency(), j.distanceMeters(), proof))));
    }

    /**
     * Rider pay (TDD 5.6): a share of the normal delivery charge with a floor for operator riders; the store pays its own.
     * The normal charge is before a free-delivery threshold or coupon, so a free delivery pays the rider the same
     * (ilyas, 2026-11-03).
     */
    static long earningFor(JobDocument job, RiderKind kind, DeliveryView rules) {
        if (kind != RiderKind.OPERATOR) {
            return 0;
        }
        long basis = Math.max(job.payBasisMinor(), job.deliveryChargeMinor());
        long share = Math.round(basis * rules.riderPayBasisPoints() / 10_000.0);
        return Math.max(share, rules.riderPayMinMinor());
    }

    private static Update assignment(RiderView rider, Instant now) {
        return new Update().set("riderId", rider.riderId()).set("riderName", rider.name()).set("riderKind", rider.kind().name())
            .set("vehicle", rider.vehicle().name()).set("assignedAt", now);
    }

    record Change(Update update, Function<JobDocument, List<ModuleEvent>> events) {
    }

    private JobDocument changeOrFail(JobDocument job, Set<DeliveryStatus> from, DeliveryStatus to,
            BiFunction<JobDocument, Instant, Change> change) {
        JobDocument after = change(job, from, to, change);
        if (after == null) {
            throw changed();
        }
        return after;
    }

    /**
     * Moves a job from one of {@code from} to {@code to} if nobody changed it meanwhile: the job,
     * its open-job row and the events in one transaction, then a live message. {@code null} when
     * the job moved on or is not in {@code from}.
     */
    private JobDocument change(JobDocument job, Set<DeliveryStatus> from, DeliveryStatus to,
            BiFunction<JobDocument, Instant, Change> change) {
        if (!from.contains(status(job))) {
            return null;
        }
        Instant now = this.clock.instant();
        Change planned = change.apply(job, now);
        Update update = planned.update().set("status", to.name()).set("updatedAt", now).inc("revision", 1);
        JobDocument after = this.transaction.execute((tx) -> {
            long matched = this.mongo.updateFirst(Query.query(Criteria.where("_id").is(job.id()).and("revision").is(job.revision())),
                    update, JobDocument.class).getMatchedCount();
            if (matched == 0) {
                return null;
            }
            JobDocument changed = this.mongo.findById(job.id(), JobDocument.class);
            if (to.isFinal()) {
                this.mongo.remove(Query.query(Criteria.where("_id").is(job.id())), ActiveJobDocument.class);
            }
            else {
                this.mongo.save(active(changed));
            }
            planned.events().apply(changed).forEach(this.outbox::append);
            return changed;
        });
        if (after != null) {
            this.live.publish(after.id(), after.pickupLat(), after.pickupLng(), after.status(), after.riderId(), "job");
            if (job.riderId() != null && !job.riderId().equals(after.riderId())) {
                this.live.publish(after.id(), after.pickupLat(), after.pickupLng(), after.status(), job.riderId(), "job");
            }
        }
        return after;
    }

    private static ActiveJobDocument active(JobDocument j) {
        return new ActiveJobDocument(j.id(), j.status(), j.mode(), j.storeId(), j.riderId(), j.offerRiderId(), j.offerExpiresAt(),
                j.searchFrom(), j.createdAt());
    }

    private JobDocument requireMine(String orderId) {
        String me = requireRider();
        JobDocument job = require(orderId);
        if (!me.equals(job.riderId())) {
            throw notFound();
        }
        return job;
    }

    private DeliveryView rules() {
        return this.serviceCalls.call(() -> this.settings.getDelivery());
    }

    private long roadDistance(GeoPoint from, GeoPoint to) {
        try {
            return this.serviceCalls.call(() -> this.geo.route(new RouteRequest(from, to))).distanceMeters();
        }
        catch (RuntimeException ex) {
            return 0;
        }
    }

    private static DeliveryStatus status(JobDocument job) {
        return DeliveryStatus.valueOf(job.status());
    }

    private static boolean isRiderActor(String actor) {
        return actor != null && actor.startsWith("rider:");
    }

    private static String actor() {
        String id = CurrentUser.id();
        return CurrentUser.hasAuthority(MANAGE) ? "support:" + id : "store:" + id;
    }

    private static void requireSupport() {
        if (!CurrentUser.hasAuthority(MANAGE)) {
            throw new ModuleException.Forbidden("Only dispatch staff can do this", "DISPATCH_ACCESS_DENIED");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ModuleException.BadRequest("Give a reason", "REASON_REQUIRED");
        }
        return reason.strip();
    }

    private String decrypt(String cipher) {
        return (cipher != null) ? this.pii.decrypt(cipher) : null;
    }

    static String hash(String code) {
        if (code == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ModuleException notFound() {
        return new ModuleException.NotFound("No such delivery", "DELIVERY_NOT_FOUND");
    }

    private static ModuleException changed() {
        return new ModuleException.Conflict("This delivery changed, reload and try again", "DELIVERY_STATUS_CHANGED");
    }
}
