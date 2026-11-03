package io.github.ilyaslabs.foodstack.rider.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.core.persistence.OnboardingDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.OnboardingDocument.IdDocument;
import io.github.ilyaslabs.foodstack.rider.core.persistence.OnboardingDocument.Payout;
import io.github.ilyaslabs.foodstack.rider.core.persistence.RiderDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * An operator rider's papers (PRD DR-1): identity and vehicle documents with photos, checked by
 * operator staff, and where the rider's pay goes. Store riders are paid by their store, so they
 * have none. Only operator staff ({@code rider:manage}) read or change them; numbers are kept
 * encrypted and shown by their last 4 characters.
 */
public class RiderOnboarding {

    private static final Logger log = LoggerFactory.getLogger(RiderOnboarding.class);

    /** Document kinds; an identity document is what makes the papers complete. */
    public static final Set<String> TYPES = Set.of("NATIONAL_ID", "PASSPORT", "DRIVING_LICENCE", "VEHICLE_REGISTRATION", "OTHER");

    static final Set<String> IDENTITY = Set.of("NATIONAL_ID", "PASSPORT");

    public static final Set<String> METHODS = Set.of("BANK", "MOBILE_WALLET");

    static final int MAX_DOCUMENTS = 10;

    private final MongoTemplate mongo;

    private final RiderService riders;

    private final MediaApi media;

    private final ServiceCalls serviceCalls;

    private final PiiCipher pii;

    private final Clock clock;

    public RiderOnboarding(MongoTemplate mongo, RiderService riders, MediaApi media, ServiceCalls serviceCalls, PiiCipher pii,
            Clock clock) {
        this.mongo = mongo;
        this.riders = riders;
        this.media = media;
        this.serviceCalls = serviceCalls;
        this.pii = pii;
        this.clock = clock;
    }

    /** What a rider's papers look like to operator staff. */
    public Papers get(String riderId) {
        requireManage();
        requireOperatorRider(riderId);
        return view(riderId, load(riderId));
    }

    /** The operator riders whose papers are complete, for the "papers missing" mark on the fleet list. */
    public List<String> completeRiders() {
        requireManage();
        List<String> ids = new ArrayList<>();
        for (OnboardingDocument doc : this.mongo.findAll(OnboardingDocument.class)) {
            if (doc.payout() != null && hasIdentity(doc)) {
                ids.add(doc.id());
            }
        }
        return ids;
    }

    /** Adds a document with its photos (uploaded first as {@code RIDER_DOCUMENT} media). */
    public Papers addDocument(String riderId, DocumentInput input) {
        requireManage();
        requireOperatorRider(riderId);
        Map<String, String> errors = new LinkedHashMap<>();
        String type = (input.type() != null) ? input.type().strip().toUpperCase(Locale.ROOT) : "";
        if (!TYPES.contains(type)) {
            errors.put("type", "must be one of " + String.join(", ", TYPES.stream().sorted().toList()));
        }
        String number = (input.number() != null) ? input.number().strip() : "";
        if (number.length() < 3 || number.length() > 40) {
            errors.put("number", "must be 3 to 40 characters");
        }
        if (input.frontMediaId() == null || input.frontMediaId().isBlank()) {
            errors.put("frontMediaId", "a photo of the document is required");
        }
        String expiresOn = blankToNull(input.expiresOn());
        if (expiresOn != null) {
            try {
                LocalDate.parse(expiresOn);
            }
            catch (DateTimeParseException ex) {
                errors.put("expiresOn", "must be a date like 2028-12-31");
            }
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("Check the document", "RIDER_DOCUMENT_INVALID", errors);
        }
        OnboardingDocument current = load(riderId);
        if (current.documents().size() >= MAX_DOCUMENTS) {
            throw new ModuleException.BadRequest("A rider can keep at most " + MAX_DOCUMENTS + " documents; remove an old one first",
                    "RIDER_DOCUMENT_LIMIT");
        }
        String id = Ids.newId();
        MediaOwner owner = owner(id);
        String back = blankToNull(input.backMediaId());
        this.serviceCalls.call(() -> this.media.claim(input.frontMediaId().strip(), MediaPurpose.RIDER_DOCUMENT, owner));
        if (back != null) {
            this.serviceCalls.call(() -> this.media.claim(back, MediaPurpose.RIDER_DOCUMENT, owner));
        }
        List<IdDocument> documents = new ArrayList<>(current.documents());
        documents.add(new IdDocument(id, type, this.pii.encrypt(number), last4(number), input.frontMediaId().strip(), back,
                expiresOn, this.clock.instant(), RiderService.actor(), null, null));
        return view(riderId, save(current, documents, current.payout()));
    }

    /** Staff checked the document against its photos. */
    public Papers verifyDocument(String riderId, String documentId) {
        requireManage();
        OnboardingDocument current = load(riderId);
        List<IdDocument> documents = new ArrayList<>();
        boolean found = false;
        for (IdDocument d : current.documents()) {
            if (d.id().equals(documentId)) {
                found = true;
                d = new IdDocument(d.id(), d.type(), d.numberCipher(), d.numberLast4(), d.frontMediaId(), d.backMediaId(),
                        d.expiresOn(), d.addedAt(), d.addedBy(), this.clock.instant(), RiderService.actor());
            }
            documents.add(d);
        }
        if (!found) {
            throw notFound();
        }
        return view(riderId, save(current, documents, current.payout()));
    }

    /** Removes a document; its photos are released for cleanup. */
    public Papers removeDocument(String riderId, String documentId) {
        requireManage();
        OnboardingDocument current = load(riderId);
        IdDocument removed = current.documents().stream().filter((d) -> d.id().equals(documentId)).findFirst()
            .orElseThrow(RiderOnboarding::notFound);
        List<IdDocument> documents = new ArrayList<>(current.documents());
        documents.remove(removed);
        OnboardingDocument saved = save(current, documents, current.payout());
        release(removed);
        return view(riderId, saved);
    }

    /** Sets where the rider's pay goes; the whole account number is entered again on every change. */
    public Papers setPayout(String riderId, PayoutInput input) {
        requireManage();
        requireOperatorRider(riderId);
        Map<String, String> errors = new LinkedHashMap<>();
        String method = (input.method() != null) ? input.method().strip().toUpperCase(Locale.ROOT) : "";
        if (!METHODS.contains(method)) {
            errors.put("method", "must be BANK or MOBILE_WALLET");
        }
        String name = (input.accountName() != null) ? input.accountName().strip() : "";
        if (name.isEmpty() || name.length() > 80) {
            errors.put("accountName", "must be 1 to 80 characters");
        }
        String provider = (input.provider() != null) ? input.provider().strip() : "";
        if (provider.isEmpty() || provider.length() > 60) {
            errors.put("provider", "must be 1 to 60 characters");
        }
        String account = (input.account() != null) ? input.account().replace(" ", "").strip() : "";
        if (account.length() < 4 || account.length() > 40) {
            errors.put("account", "must be 4 to 40 characters");
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("Check the payout details", "RIDER_PAYOUT_INVALID", errors);
        }
        OnboardingDocument current = load(riderId);
        Payout payout = new Payout(method, name, provider, this.pii.encrypt(account), last4(account), this.clock.instant(),
                RiderService.actor());
        return view(riderId, save(current, current.documents(), payout));
    }

    // ---- reading -------------------------------------------------------------------------------

    private Papers view(String riderId, OnboardingDocument doc) {
        LocalDate today = LocalDate.now(this.clock);
        List<DocumentView> documents = new ArrayList<>();
        boolean identity = hasIdentity(doc);
        for (IdDocument d : doc.documents()) {
            boolean expired = d.expiresOn() != null && LocalDate.parse(d.expiresOn()).isBefore(today);
            documents.add(new DocumentView(d.id(), d.type(), d.numberLast4(), photo(d.frontMediaId()), photo(d.backMediaId()),
                    d.expiresOn(), expired, d.addedAt().getEpochSecond(), d.verifiedAt() != null,
                    (d.verifiedAt() != null) ? d.verifiedAt().getEpochSecond() : null));
        }
        Payout p = doc.payout();
        PayoutView payout = (p != null)
                ? new PayoutView(p.method(), p.accountName(), p.provider(), p.accountLast4(), p.updatedAt().getEpochSecond()) : null;
        return new Papers(riderId, documents, payout, identity, payout != null, identity && payout != null,
                (doc.version() != null) ? doc.version() : 0);
    }

    /** A short-lived link to the largest variant of a private photo, or {@code null}. */
    private String photo(String mediaId) {
        if (mediaId == null) {
            return null;
        }
        try {
            MediaAssetView asset = this.serviceCalls.call(() -> this.media.getAsset(mediaId));
            MediaVariantView best = null;
            for (MediaVariantView v : asset.variants().values()) {
                if (best == null || v.width() > best.width()) {
                    best = v;
                }
            }
            return (best != null) ? best.url() : null;
        }
        catch (RuntimeException ex) {
            log.debug("No photo for media {}: {}", mediaId, ex.getMessage());
            return null;
        }
    }

    private boolean hasIdentity(OnboardingDocument doc) {
        LocalDate today = LocalDate.now(this.clock);
        return doc.documents().stream().anyMatch((d) -> IDENTITY.contains(d.type()) && d.verifiedAt() != null
                && (d.expiresOn() == null || !LocalDate.parse(d.expiresOn()).isBefore(today)));
    }

    private OnboardingDocument load(String riderId) {
        OnboardingDocument doc = this.mongo.findById(riderId, OnboardingDocument.class);
        return (doc != null) ? doc : new OnboardingDocument(riderId, List.of(), null, this.clock.instant(), null);
    }

    private OnboardingDocument save(OnboardingDocument current, List<IdDocument> documents, Payout payout) {
        try {
            return this.mongo.save(new OnboardingDocument(current.id(), List.copyOf(documents), payout, this.clock.instant(),
                    current.version()));
        }
        catch (OptimisticLockingFailureException ex) {
            throw new ModuleException.Conflict("Someone else changed these papers; reload and try again",
                    "RIDER_PAPERS_CHANGED");
        }
    }

    private void release(IdDocument d) {
        MediaOwner owner = owner(d.id());
        for (String mediaId : new String[] { d.frontMediaId(), d.backMediaId() }) {
            if (mediaId == null) {
                continue;
            }
            try {
                this.serviceCalls.run(() -> this.media.release(mediaId, owner));
            }
            catch (RuntimeException ex) {
                log.warn("Photo {} of removed rider document {} not released: {}", mediaId, d.id(), ex.getMessage());
            }
        }
    }

    private void requireOperatorRider(String riderId) {
        RiderDocument rider = this.riders.require(riderId);
        if (!RiderKind.OPERATOR.name().equals(rider.kind())) {
            throw new ModuleException.BadRequest("Store riders are paid by their store and keep no papers here",
                    "RIDER_NOT_OPERATOR");
        }
    }

    private static void requireManage() {
        if (!CurrentUser.hasAuthority(RiderService.MANAGE)) {
            throw new ModuleException.Forbidden("Only operator staff can see rider papers", "RIDER_ACCESS_DENIED");
        }
    }

    private static MediaOwner owner(String documentId) {
        return new MediaOwner("rider", "doc-" + documentId);
    }

    static String last4(String value) {
        String compact = value.replaceAll("[\\s-]", "");
        return (compact.length() <= 4) ? compact : compact.substring(compact.length() - 4);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    private static ModuleException notFound() {
        return new ModuleException.NotFound("No such document", "RIDER_DOCUMENT_NOT_FOUND");
    }

    /** A new document. */
    public record DocumentInput(String type, String number, String frontMediaId, String backMediaId, String expiresOn) {

        @Override
        public String toString() {
            return "DocumentInput[type=" + this.type + "]";
        }
    }

    /** New payout details. */
    public record PayoutInput(String method, String accountName, String provider, String account) {

        @Override
        public String toString() {
            return "PayoutInput[method=" + this.method + "]";
        }
    }

    /**
     * A rider's papers.
     *
     * @param riderId rider
     * @param documents documents, oldest first
     * @param payout payout details, or {@code null}
     * @param identityVerified a checked, unexpired national ID or passport is on file
     * @param payoutSet payout details are set
     * @param complete both of the above
     * @param version changes
     */
    public record Papers(String riderId, List<DocumentView> documents, PayoutView payout, boolean identityVerified,
            boolean payoutSet, boolean complete, long version) {
    }

    /** A document as staff see it; photo links expire after a few minutes. */
    public record DocumentView(String id, String type, String numberLast4, String frontUrl, String backUrl, String expiresOn,
            boolean expired, long addedAt, boolean verified, Long verifiedAt) {
    }

    /** Payout details with the account shown by its last 4 characters. */
    public record PayoutView(String method, String accountName, String provider, String accountLast4, long updatedAt) {
    }
}
