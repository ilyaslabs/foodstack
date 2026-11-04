package io.github.ilyaslabs.foodstack.rider.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.RouteRequest;
import io.github.ilyaslabs.foodstack.geo.api.RouteView;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.rider.api.RiderCandidate;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderSearch;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.rider.api.events.RiderStatusChanged;
import io.github.ilyaslabs.foodstack.rider.core.persistence.AssignmentDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.EarningDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.LocationDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.RiderDocument;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What riders do between jobs and on them (PRD DR-2 to DR-7, TDD 5.6): going online, sending their
 * position, being found for a job, the deliveries they have (from dispatch events), what they earned,
 * and the live position the customer, the store and the operator follow.
 */
public class RiderActivity {

    private static final Logger log = LoggerFactory.getLogger(RiderActivity.class);

    /** A rider with no ping for this long is taken offline. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /** Positions are written to MongoDB (rider document and history) at most this often per rider. */
    static final Duration SAMPLE_EVERY = Duration.ofSeconds(30);

    /** Most jobs an operator rider carries at once (TDD 5.6). */
    static final int MAX_ACTIVE_JOBS = 1;

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final RiderService riders;

    private final RiderPresence presence;

    private final RateLimiter rateLimiter;

    private final SettingsApi settings;

    private final GeoApi geo;

    private final ServiceCalls serviceCalls;

    private final Clock clock;

    public RiderActivity(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, RiderService riders,
            RiderPresence presence, RateLimiter rateLimiter, SettingsApi settings, GeoApi geo, ServiceCalls serviceCalls,
            Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.riders = riders;
        this.presence = presence;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
        this.geo = geo;
        this.serviceCalls = serviceCalls;
        this.clock = clock;
    }

    // ---- the rider -----------------------------------------------------------------------------

    public RiderDetails me() {
        return this.riders.details(self());
    }

    /** Starts or ends a shift (PRD DR-2). A suspended rider cannot go online. */
    public RiderDetails setOnline(boolean online, LocationPing at) {
        RiderDocument rider = self();
        if (online && !RiderStatus.ACTIVE.name().equals(rider.status())) {
            throw new ModuleException.Forbidden("Your account is suspended, contact the operator", "RIDER_SUSPENDED");
        }
        Instant now = this.clock.instant();
        if (rider.online() != online) {
            this.transaction.executeWithoutResult((tx) -> {
                Update update = new Update().set("online", online).set("updatedAt", now);
                if (online) {
                    update.set("idleSince", now).set("lastSeenAt", now);
                }
                this.mongo.updateFirst(byId(rider.id()), update, RiderDocument.class);
                this.outbox.append(new RiderStatusChanged(rider.id(), RiderKind.valueOf(rider.kind()), rider.storeId(),
                        RiderStatus.valueOf(rider.status()), online, "rider:" + rider.id()));
            });
        }
        if (!online) {
            this.presence.forget(rider.id());
            this.presence.publishState(rider.id(), false, !rider.activeOrderIds().isEmpty());
        }
        else if (at != null) {
            ping(self(), List.of(at), now);
        }
        return this.riders.details(self());
    }

    /**
     * Positions from the rider app, oldest first: the last one is where the rider is now, earlier
     * ones were queued while the phone was offline and only go to history (PRD DR-3).
     */
    public void locations(List<LocationPing> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        if (points.size() > 120) {
            throw new ModuleException.BadRequest("Send at most 120 positions at once", "LOCATION_INVALID");
        }
        for (LocationPing point : points) {
            if (!new GeoPoint(point.lat(), point.lng()).isValid()) {
                throw new ModuleException.BadRequest("A position is not valid", "LOCATION_INVALID");
            }
        }
        String riderId = CurrentUser.id();
        this.rateLimiter.check("rider", "location", riderId, 30, Duration.ofMinutes(1));
        RiderDocument rider = self();
        if (!rider.online()) {
            throw new ModuleException.Conflict("Go online to share your position", "RIDER_OFFLINE");
        }
        ping(rider, points, this.clock.instant());
    }

    private void ping(RiderDocument rider, List<LocationPing> points, Instant now) {
        LocationPing last = points.get(points.size() - 1);
        long at = (last.at() != null) ? Math.min(last.at(), now.getEpochSecond()) : now.getEpochSecond();
        RiderPresence.Position position = new RiderPresence.Position(last.lat(), last.lng(), at);
        boolean busy = !rider.activeOrderIds().isEmpty();
        this.presence.moved(rider.id(), position, matchable(rider));
        this.presence.publish(rider.id(), position, busy, rider.kind(), rider.storeId(), rider.activeOrderIds());
        if (points.size() > 1 || this.presence.firstIn(rider.id(), "sample", SAMPLE_EVERY)) {
            this.mongo.updateFirst(byId(rider.id()), new Update().set("lat", last.lat()).set("lng", last.lng())
                .set("lastSeenAt", Instant.ofEpochSecond(at)), RiderDocument.class);
            Instant expire = now.plus(Duration.ofDays(retentionDays()));
            List<LocationDocument> samples = new ArrayList<>();
            for (LocationPing point : points) {
                Instant taken = (point.at() != null) ? Instant.ofEpochSecond(Math.min(point.at(), now.getEpochSecond())) : now;
                samples.add(new LocationDocument(Ids.newId(), rider.id(), rider.activeOrderIds(), point.lat(), point.lng(),
                        point.accuracyMeters(), taken, expire));
            }
            this.mongo.insertAll(samples);
        }
    }

    private int retentionDays() {
        try {
            return Math.max(1, this.serviceCalls.call(() -> this.settings.getDelivery()).locationRetentionDays());
        }
        catch (RuntimeException ex) {
            return 30;
        }
    }

    /** Takes riders whose phone went quiet offline; returns how many. */
    public int sweepStale() {
        Instant cutoff = this.clock.instant().minus(STALE_AFTER);
        List<RiderDocument> stale = this.mongo.find(Query.query(Criteria.where("online").is(true).and("lastSeenAt").lt(cutoff))
            .limit(200), RiderDocument.class);
        int count = 0;
        for (RiderDocument rider : stale) {
            boolean changed = this.transaction.execute((tx) -> {
                long modified = this.mongo.updateFirst(Query.query(Criteria.where("_id").is(rider.id()).and("online").is(true)
                    .and("lastSeenAt").lt(cutoff)), new Update().set("online", false), RiderDocument.class).getModifiedCount();
                if (modified > 0) {
                    this.outbox.append(new RiderStatusChanged(rider.id(), RiderKind.valueOf(rider.kind()), rider.storeId(),
                            RiderStatus.valueOf(rider.status()), false, "system"));
                }
                return modified > 0;
            });
            if (changed) {
                this.presence.forget(rider.id());
                this.presence.publishState(rider.id(), false, !rider.activeOrderIds().isEmpty());
                count++;
            }
        }
        return count;
    }

    // ---- matching (dispatch) -------------------------------------------------------------------

    /** Free, online, active operator riders near a point, nearest first. */
    public List<RiderCandidate> findAvailable(RiderSearch search) {
        int limit = Math.max(1, Math.min(search.limit(), 20));
        Set<String> exclude = (search.excludeRiderIds() != null) ? search.excludeRiderIds() : Set.of();
        List<RiderPresence.Nearby> nearby = this.presence.near(search.lat(), search.lng(), search.radiusMeters(),
                limit * 3 + exclude.size());
        Instant now = this.clock.instant();
        Instant fresh = now.minus(STALE_AFTER);
        List<RiderCandidate> candidates = new ArrayList<>();
        for (RiderPresence.Nearby near : nearby) {
            if (exclude.contains(near.riderId())) {
                continue;
            }
            RiderDocument rider = this.riders.find(near.riderId());
            if (rider == null || !matchable(rider)) {
                this.presence.unmatchable(near.riderId());
                continue;
            }
            // the last ping in Redis is current; the copy in MongoDB is sampled
            RiderPresence.Position position = this.presence.position(rider.id());
            if (position == null || position.at() < fresh.getEpochSecond()) {
                continue;
            }
            long idle = (rider.idleSince() != null) ? Math.max(0, Duration.between(rider.idleSince(), now).toSeconds()) : 0;
            candidates.add(new RiderCandidate(rider.id(), rider.name(), Vehicle.valueOf(rider.vehicle()), near.distanceMeters(),
                    idle, RiderService.acceptancePercent(rider)));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return candidates;
    }

    private static boolean matchable(RiderDocument rider) {
        return rider.online() && RiderKind.OPERATOR.name().equals(rider.kind())
                && RiderStatus.ACTIVE.name().equals(rider.status()) && rider.activeOrderIds().size() < MAX_ACTIVE_JOBS;
    }

    // ---- deliveries (dispatch events) ----------------------------------------------------------

    /** An offer went to the rider: counts towards the acceptance rate. */
    public void offered(String riderId) {
        this.mongo.updateFirst(byId(riderId), new Update().inc("offers", 1), RiderDocument.class);
    }

    /** The rider has the delivery now. */
    public void assigned(AssignmentDocument assignment, boolean acceptedOffer) {
        try {
            this.mongo.insert(assignment);
        }
        catch (DuplicateKeyException ex) {
            this.mongo.save(assignment);
        }
        Update update = new Update().addToSet("activeOrderIds", assignment.id());
        if (acceptedOffer) {
            update.inc("accepted", 1);
        }
        RiderDocument rider = this.mongo.findAndModify(byId(assignment.riderId()), update,
                FindAndModifyOptions.options().returnNew(true), RiderDocument.class);
        if (rider != null && !matchable(rider)) {
            this.presence.unmatchable(rider.id());
        }
    }

    public void pickedUp(String orderId) {
        this.mongo.updateFirst(byId(orderId), new Update().set("pickedUp", true), AssignmentDocument.class);
    }

    /** The rider no longer has the delivery (unassigned, cancelled, handed back or delivered). */
    public void released(String orderId, String riderId) {
        AssignmentDocument assignment = this.mongo.findById(orderId, AssignmentDocument.class);
        String rider = (riderId != null) ? riderId : (assignment != null) ? assignment.riderId() : null;
        if (assignment != null && (riderId == null || riderId.equals(assignment.riderId()))) {
            this.mongo.remove(byId(orderId), AssignmentDocument.class);
        }
        if (rider == null) {
            return;
        }
        RiderDocument updated = this.mongo.findAndModify(byId(rider),
                new Update().pull("activeOrderIds", orderId).set("idleSince", this.clock.instant()),
                FindAndModifyOptions.options().returnNew(true), RiderDocument.class);
        if (updated != null && matchable(updated)) {
            this.presence.matchable(updated.id());
        }
    }

    /** A delivery was completed: the rider's earning is recorded once (id = order id). */
    public void delivered(EarningDocument earning) {
        try {
            this.mongo.insert(earning);
            this.mongo.updateFirst(byId(earning.riderId()), new Update().inc("deliveries", 1), RiderDocument.class);
        }
        catch (DuplicateKeyException ex) {
            log.debug("Earning for order {} already recorded", earning.id());
        }
        released(earning.id(), earning.riderId());
    }

    // ---- earnings ------------------------------------------------------------------------------

    public Earnings myEarnings(int days, String timeZone) {
        return earnings(self().id(), days, timeZone);
    }

    public Earnings earningsOf(String riderId, int days, String timeZone) {
        RiderDocument rider = this.riders.require(riderId);
        if (!CurrentUser.hasAuthority(RiderService.MANAGE)
                && !(rider.storeId() != null && CurrentUser.storeIds().contains(rider.storeId()))) {
            throw new ModuleException.NotFound("No such rider", "RIDER_NOT_FOUND");
        }
        return earnings(riderId, days, timeZone);
    }

    private Earnings earnings(String riderId, int days, String timeZone) {
        int span = Math.max(1, Math.min(days, 31));
        ZoneId zone = zone(timeZone);
        LocalDate today = LocalDate.ofInstant(this.clock.instant(), zone);
        LocalDate first = today.minusDays(span - 1L);
        Instant from = first.atStartOfDay(zone).toInstant();
        List<EarningDocument> rows = this.mongo.find(Query.query(Criteria.where("riderId").is(riderId).and("at").gte(from))
            .with(Sort.by(Sort.Direction.DESC, "at")).limit(2000), EarningDocument.class);
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate day = today; !day.isBefore(first); day = day.minusDays(1)) {
            byDay.put(day, new long[4]);
        }
        String currency = null;
        for (EarningDocument row : rows) {
            long[] totals = byDay.get(LocalDate.ofInstant(row.at(), zone));
            if (totals != null) {
                totals[0]++;
                totals[1] += row.earningMinor();
                totals[2] += row.tipMinor();
                totals[3] += row.cashCollectedMinor();
            }
            currency = (currency != null) ? currency : row.currency();
        }
        List<Earnings.Day> list = byDay.entrySet().stream().map((e) -> new Earnings.Day(e.getKey().toString(),
                (int) e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3])).toList();
        List<Earnings.Row> recent = rows.stream().limit(20).map((r) -> new Earnings.Row(r.id(), r.orderNumber(), r.storeName(),
                r.earningMinor(), r.tipMinor(), r.cashCollectedMinor(), r.distanceMeters(), r.at().getEpochSecond())).toList();
        if (currency == null) {
            // nothing earned in the period: show zeros in the operator's currency
            currency = this.serviceCalls.call(() -> this.settings.getLocale()).currency();
        }
        return new Earnings(currency, list.get(0), list, recent);
    }

    private static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone);
        }
        catch (RuntimeException ex) {
            throw new ModuleException.BadRequest("Unknown time zone", "TIME_ZONE_INVALID");
        }
    }

    // ---- following a delivery -------------------------------------------------------------------

    /** Checks the caller may follow a delivery: its customer, its store's people or operator staff. */
    public AssignmentDocument requireFollow(String orderId) {
        AssignmentDocument assignment = this.mongo.findById(orderId, AssignmentDocument.class);
        String me = CurrentUser.id();
        if (assignment != null && ((me != null && (me.equals(assignment.customerId()) || me.equals(assignment.riderId())))
                || CurrentUser.storeIds().contains(assignment.storeId()) || CurrentUser.hasAuthority(RiderService.DISPATCH)
                || CurrentUser.hasAuthority(RiderService.MANAGE))) {
            return assignment;
        }
        throw new ModuleException.NotFound("Nobody is delivering this order yet", "TRACKING_NOT_FOUND");
    }

    /** Where the rider is and how long until the customer has the food (PRD CU-9). */
    public Tracking tracking(String orderId) {
        AssignmentDocument a = requireFollow(orderId);
        RiderPresence.Position position = this.presence.position(a.riderId());
        Long eta = null;
        Long remaining = null;
        if (position != null) {
            GeoPoint rider = new GeoPoint(position.lat(), position.lng());
            GeoPoint store = new GeoPoint(a.pickupLat(), a.pickupLng());
            GeoPoint home = new GeoPoint(a.dropoffLat(), a.dropoffLng());
            double slower = slowdown(a.vehicle());
            try {
                RouteView last = this.serviceCalls.call(() -> this.geo.route(new RouteRequest(a.pickedUp() ? rider : store, home)));
                long seconds = last.durationSeconds();
                long meters = last.distanceMeters();
                if (!a.pickedUp()) {
                    RouteView first = this.serviceCalls.call(() -> this.geo.route(new RouteRequest(rider, store)));
                    seconds += first.durationSeconds();
                    meters += first.distanceMeters();
                }
                eta = Math.round(seconds * slower);
                remaining = meters;
            }
            catch (RuntimeException ex) {
                log.debug("No ETA for order {}: {}", orderId, ex.getMessage());
            }
        }
        return new Tracking(a.id(), a.riderName(), a.vehicle(), a.pickedUp(), (position != null) ? position.lat() : null,
                (position != null) ? position.lng() : null, (position != null) ? position.at() : null, a.pickupLat(),
                a.pickupLng(), a.dropoffLat(), a.dropoffLng(), eta, remaining);
    }

    private static double slowdown(String vehicle) {
        try {
            return Math.max(1.0, 25.0 / Vehicle.valueOf(vehicle).urbanSpeedKmh());
        }
        catch (RuntimeException ex) {
            return 1.0;
        }
    }

    /** Every online rider with a position, for the operator's live map (PRD AD-9). */
    public List<LiveRider> live() {
        return live(null);
    }

    /**
     * Online riders for a live map, those last seen inside {@code view} when given (TDD 7.5): a map
     * shows one area, so it never loads every rider of a country.
     */
    public List<LiveRider> live(MapBounds view) {
        if (!CurrentUser.hasAuthority(RiderService.MANAGE) && !CurrentUser.hasAuthority(RiderService.DISPATCH)) {
            throw new ModuleException.Forbidden("Only operator staff can see the live map", "RIDER_ACCESS_DENIED");
        }
        Criteria criteria = Criteria.where("online").is(true);
        if (view != null) {
            criteria = criteria.and("lat").gte(view.south()).lte(view.north()).and("lng").gte(view.west()).lte(view.east());
        }
        List<RiderDocument> online = this.mongo.find(Query.query(criteria).limit(MAX_LIVE), RiderDocument.class);
        List<RiderPresence.Position> positions = this.presence.positions(online.stream().map(RiderDocument::id).toList());
        List<LiveRider> live = new ArrayList<>(online.size());
        for (int i = 0; i < online.size(); i++) {
            RiderDocument r = online.get(i);
            RiderPresence.Position p = positions.get(i);
            live.add(new LiveRider(r.id(), r.name(), RiderKind.valueOf(r.kind()), r.storeId(), Vehicle.valueOf(r.vehicle()),
                    !r.activeOrderIds().isEmpty(), (p != null) ? p.lat() : r.lat(), (p != null) ? p.lng() : r.lng(),
                    (p != null) ? Long.valueOf(p.at())
                            : (r.lastSeenAt() != null) ? Long.valueOf(r.lastSeenAt().getEpochSecond()) : null));
        }
        live.sort(Comparator.comparing(LiveRider::name));
        return live;
    }

    /** The most riders one live map loads. */
    static final int MAX_LIVE = 2_000;

    /** A map's visible area. */
    public record MapBounds(double south, double west, double north, double east) {
    }

    public void requireLive() {
        if (!CurrentUser.hasAuthority(RiderService.MANAGE) && !CurrentUser.hasAuthority(RiderService.DISPATCH)) {
            throw new ModuleException.Forbidden("Only operator staff can see the live map", "RIDER_ACCESS_DENIED");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private RiderDocument self() {
        String id = CurrentUser.id();
        if (id == null) {
            throw new ModuleException.Unauthorized("Sign in first", "UNAUTHENTICATED");
        }
        RiderDocument rider = this.riders.find(id);
        if (rider == null) {
            throw new ModuleException.NotFound("You are not a rider", "RIDER_NOT_FOUND");
        }
        return rider;
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(id));
    }
}
