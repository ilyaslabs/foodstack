package io.github.ilyaslabs.foodstack.rider.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.geo.api.CityView;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.RiderAccessGrant;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;
import io.github.ilyaslabs.foodstack.rider.api.Vehicle;
import io.github.ilyaslabs.foodstack.rider.api.events.RiderStatusChanged;
import io.github.ilyaslabs.foodstack.rider.core.persistence.RiderDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rider profiles (PRD DR-1): the operator adds and manages its fleet ({@code rider:manage}); a store
 * adds and removes its own riders. Adding gives the person rider access in identity (as the platform,
 * after this module checked the caller's right); the rider's id is the user id.
 */
public class RiderService {

    public static final String MANAGE = "rider:manage";

    public static final String PORTAL = "rider:portal";

    public static final String DISPATCH = "dispatch:manage";

    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{6,20}$");

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final OutboxWriter outbox;

    private final IdentityApi identity;

    private final ServiceCalls serviceCalls;

    private final RiderPresence presence;

    private final PiiCipher pii;

    private final Clock clock;

    private final GeoApi geo;

    public RiderService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, IdentityApi identity,
            ServiceCalls serviceCalls, RiderPresence presence, PiiCipher pii, Clock clock) {
        this(mongo, transaction, outbox, identity, serviceCalls, presence, pii, clock, null);
    }

    /**
     * @param geo the operator's cities, to check a rider's city; {@code null} accepts no city
     */
    public RiderService(MongoTemplate mongo, TransactionTemplate transaction, OutboxWriter outbox, IdentityApi identity,
            ServiceCalls serviceCalls, RiderPresence presence, PiiCipher pii, Clock clock, GeoApi geo) {
        this.geo = geo;
        this.mongo = mongo;
        this.transaction = transaction;
        this.outbox = outbox;
        this.identity = identity;
        this.serviceCalls = serviceCalls;
        this.presence = presence;
        this.pii = pii;
        this.clock = clock;
    }

    // ---- operator ------------------------------------------------------------------------------

    /** The fleet and store riders, filtered; operator staff only. */
    public List<RiderDetails> list(RiderKind kind, RiderStatus status, String storeId, String text) {
        requireManage();
        Criteria criteria = new Criteria();
        if (kind != null) {
            criteria = criteria.and("kind").is(kind.name());
        }
        if (status != null) {
            criteria = criteria.and("status").is(status.name());
        }
        if (storeId != null && !storeId.isBlank()) {
            criteria = criteria.and("storeId").is(storeId);
        }
        if (text != null && !text.isBlank()) {
            criteria = criteria.and("name").regex(Pattern.quote(text.strip()), "i");
        }
        Query query = Query.query(criteria).with(Sort.by("name")).limit(500);
        return this.mongo.find(query, RiderDocument.class).stream().map(this::details).toList();
    }

    /** Adds an operator rider. */
    public RiderDetails addOperatorRider(RiderInput input) {
        requireManage();
        return add(input, RiderKind.OPERATOR, null);
    }

    public RiderDetails get(String riderId) {
        RiderDocument rider = require(riderId);
        requireView(rider);
        return details(rider);
    }

    /** Changes a rider's profile; operator staff, or the store for its own riders. */
    public RiderDetails update(String riderId, RiderInput input) {
        RiderDocument rider = require(riderId);
        requireEdit(rider);
        Clean clean = clean(input);
        if (input.version() == null || input.version() != rider.version().longValue()) {
            throw changed();
        }
        Update update = new Update().set("name", clean.name()).set("phoneCipher", this.pii.encrypt(clean.phone()))
            .set("vehicle", clean.vehicle().name()).set("plate", clean.plate()).set("city", clean.city()).set("cityId", clean.cityId())
            .set("updatedAt", this.clock.instant()).inc("version", 1);
        long matched = this.mongo.updateFirst(Query.query(Criteria.where("_id").is(riderId).and("version").is(rider.version())),
                update, RiderDocument.class).getMatchedCount();
        if (matched == 0) {
            throw changed();
        }
        return details(require(riderId));
    }

    /** Takes a rider off the road or back on (PRD DR-1). A suspended rider goes offline at once. */
    public RiderDetails setStatus(String riderId, RiderStatus status) {
        RiderDocument rider = require(riderId);
        requireEdit(rider);
        if (rider.status().equals(status.name())) {
            return details(rider);
        }
        Instant now = this.clock.instant();
        this.transaction.executeWithoutResult((tx) -> {
            Update update = new Update().set("status", status.name()).set("updatedAt", now);
            if (status == RiderStatus.SUSPENDED) {
                update.set("online", false);
            }
            this.mongo.updateFirst(Query.query(Criteria.where("_id").is(riderId)), update, RiderDocument.class);
            this.outbox.append(new RiderStatusChanged(riderId, RiderKind.valueOf(rider.kind()), rider.storeId(), status,
                    status == RiderStatus.ACTIVE && rider.online(), actor()));
        });
        if (status == RiderStatus.SUSPENDED) {
            this.presence.forget(riderId);
            this.presence.publishState(riderId, false, false);
        }
        return details(require(riderId));
    }

    // ---- store ---------------------------------------------------------------------------------

    /** A store's own riders, for its people and operator staff. */
    public List<RiderDetails> storeRiders(String storeId) {
        CurrentUser.requireStoreAccess(storeId, MANAGE);
        return this.mongo.find(Query.query(Criteria.where("kind").is(RiderKind.STORE.name()).and("storeId").is(storeId))
            .with(Sort.by("name")), RiderDocument.class).stream().map(this::details).toList();
    }

    public RiderDetails addStoreRider(String storeId, RiderInput input) {
        if (!ModuleAccess.isService()) {
            CurrentUser.requireStoreAccess(storeId, MANAGE);
        }
        return add(input, RiderKind.STORE, storeId);
    }

    /** Removes a store's rider and their rider access; not while they have a delivery. */
    public void removeStoreRider(String storeId, String riderId) {
        CurrentUser.requireStoreAccess(storeId, MANAGE);
        RiderDocument rider = require(riderId);
        if (!RiderKind.STORE.name().equals(rider.kind()) || !storeId.equals(rider.storeId())) {
            throw notFound();
        }
        if (!rider.activeOrderIds().isEmpty()) {
            throw new ModuleException.Conflict("The rider has a delivery now", "RIDER_BUSY");
        }
        this.transaction.executeWithoutResult((tx) -> {
            this.mongo.remove(Query.query(Criteria.where("_id").is(riderId)), RiderDocument.class);
            this.outbox.append(new RiderStatusChanged(riderId, RiderKind.STORE, storeId, RiderStatus.SUSPENDED, false, actor()));
        });
        this.presence.forget(riderId);
        this.serviceCalls.run(() -> this.identity.revokeRiderAccess(riderId));
    }

    // ---- reading -------------------------------------------------------------------------------

    public RiderDocument require(String riderId) {
        RiderDocument rider = (riderId != null) ? this.mongo.findById(riderId, RiderDocument.class) : null;
        if (rider == null) {
            throw notFound();
        }
        return rider;
    }

    public RiderDocument find(String riderId) {
        return this.mongo.findById(riderId, RiderDocument.class);
    }

    /** A rider without access checks, for platform calls. */
    public RiderDetails details(String riderId) {
        return details(require(riderId));
    }

    RiderDetails details(RiderDocument r) {
        String phone = null;
        if (r.phoneCipher() != null) {
            try {
                phone = this.pii.decrypt(r.phoneCipher());
            }
            catch (RuntimeException ex) {
                phone = null;
            }
        }
        return new RiderDetails(r.id(), RiderKind.valueOf(r.kind()), r.storeId(), r.name(), phone, Vehicle.valueOf(r.vehicle()),
                r.plate(), r.city(), RiderStatus.valueOf(r.status()), r.online(), r.lat(), r.lng(),
                (r.lastSeenAt() != null) ? r.lastSeenAt().getEpochSecond() : null, r.activeOrderIds(),
                acceptancePercent(r), r.deliveries(), r.createdAt().getEpochSecond(), (r.version() != null) ? r.version() : 0,
                r.cityId());
    }

    static int acceptancePercent(RiderDocument r) {
        return (r.offers() == 0) ? 100 : (int) Math.min(100, Math.round(100.0 * r.accepted() / r.offers()));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private RiderDetails add(RiderInput input, RiderKind kind, String storeId) {
        Clean clean = clean(input);
        String email = (input.email() != null) ? input.email().strip().toLowerCase(Locale.ROOT) : "";
        if (!email.contains("@")) {
            throw new ModuleException.BadRequest("Enter the rider's email", "RIDER_INVALID");
        }
        UserView user = this.serviceCalls.call(() -> this.identity.grantRiderAccess(
                new RiderAccessGrant(email, clean.name(), input.initialPassword(), storeId, CurrentUser.id())));
        if (this.mongo.findById(user.id(), RiderDocument.class) != null) {
            throw new ModuleException.Conflict("This person is a rider already", "RIDER_EXISTS");
        }
        Instant now = this.clock.instant();
        RiderDocument rider = new RiderDocument(user.id(), kind.name(), storeId, clean.name(), this.pii.encrypt(clean.phone()),
                clean.vehicle().name(), clean.plate(), clean.city(), clean.cityId(), RiderStatus.ACTIVE.name(), false, now, null, null, null,
                new ArrayList<>(), 0, 0, 0, now, now, null);
        RiderDocument saved = this.transaction.execute((tx) -> {
            RiderDocument inserted = this.mongo.insert(rider);
            this.outbox.append(new RiderStatusChanged(inserted.id(), kind, storeId, RiderStatus.ACTIVE, false, actor()));
            return inserted;
        });
        return details(saved);
    }

    private record Clean(String name, String phone, Vehicle vehicle, String plate, String city, String cityId) {
    }

    private Clean clean(RiderInput input) {
        String name = (input.name() != null) ? input.name().strip() : "";
        if (name.isEmpty() || name.length() > 60) {
            throw new ModuleException.BadRequest("Enter the rider's name (up to 60 characters)", "RIDER_INVALID");
        }
        String phone = (input.phone() != null) ? input.phone().strip() : "";
        if (!PHONE.matcher(phone).matches()) {
            throw new ModuleException.BadRequest("Enter a valid phone number", "RIDER_PHONE_INVALID");
        }
        if (input.vehicle() == null) {
            throw new ModuleException.BadRequest("Choose the rider's vehicle", "RIDER_INVALID");
        }
        String plate = (input.plate() != null && !input.plate().isBlank()) ? input.plate().strip().toUpperCase(Locale.ROOT)
                : null;
        if (plate != null && plate.length() > 20) {
            throw new ModuleException.BadRequest("The number plate is too long", "RIDER_INVALID");
        }
        String city = (input.city() != null && !input.city().isBlank()) ? input.city().strip() : null;
        if (city != null && city.length() > 60) {
            throw new ModuleException.BadRequest("The city name is too long", "RIDER_INVALID");
        }
        String cityId = (input.cityId() != null && !input.cityId().isBlank()) ? input.cityId().strip() : null;
        if (cityId != null) {
            CityView chosen = (this.geo != null) ? this.serviceCalls.call(this.geo::cities)
                .stream()
                .filter((c) -> c.id().equals(input.cityId().strip()))
                .findFirst()
                .orElse(null) : null;
            if (chosen == null) {
                throw new ModuleException.BadRequest("Choose one of the operator's cities", "RIDER_CITY_UNKNOWN");
            }
            if (city == null && chosen.name() != null && !chosen.name().isEmpty()) {
                city = chosen.name().values().iterator().next();
            }
        }
        return new Clean(name, phone, input.vehicle(), plate, city, cityId);
    }

    private static void requireManage() {
        if (!CurrentUser.hasAuthority(MANAGE) && !ModuleAccess.isService()) {
            throw new ModuleException.Forbidden("Only operator staff can do this", "RIDER_ACCESS_DENIED");
        }
    }

    private static void requireView(RiderDocument rider) {
        String me = CurrentUser.id();
        if (rider.id().equals(me) || CurrentUser.hasAuthority(MANAGE) || CurrentUser.hasAuthority(DISPATCH)) {
            return;
        }
        if (rider.storeId() != null && CurrentUser.managesStore(rider.storeId())) {
            return;
        }
        throw notFound();
    }

    private static void requireEdit(RiderDocument rider) {
        if (CurrentUser.hasAuthority(MANAGE)) {
            return;
        }
        if (RiderKind.STORE.name().equals(rider.kind()) && CurrentUser.managesStore(rider.storeId())) {
            return;
        }
        throw new ModuleException.Forbidden("You cannot change this rider", "RIDER_ACCESS_DENIED");
    }

    static String actor() {
        String id = CurrentUser.id();
        if (id == null) {
            return "system";
        }
        if (CurrentUser.hasAuthority(MANAGE)) {
            return "support:" + id;
        }
        if (CurrentUser.hasAuthority(PORTAL)) {
            return "rider:" + id;
        }
        return "store:" + id;
    }

    private static ModuleException notFound() {
        return new ModuleException.NotFound("No such rider", "RIDER_NOT_FOUND");
    }

    private static ModuleException changed() {
        return new ModuleException.Conflict("Someone changed this rider, reload and try again", "RIDER_CHANGED");
    }
}
