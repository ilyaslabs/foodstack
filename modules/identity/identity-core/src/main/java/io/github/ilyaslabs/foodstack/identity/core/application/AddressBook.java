package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.core.persistence.AddressDocument;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customers' saved addresses (PRD ID-6): a map pin, a label (Home, Work or the customer's own name),
 * the address line and notes for the rider. One address is preferred: the apps start with it. A
 * customer has at most one Home and one Work and {@value #MAX_ADDRESSES} addresses in all. Every
 * query includes {@code userId}, the shard key, so it goes to one shard.
 */
public class AddressBook {

    /** Most addresses one customer keeps. */
    public static final int MAX_ADDRESSES = 20;

    static final Set<String> LABELS = Set.of("HOME", "WORK", "OTHER");

    private static final Set<String> SINGLE_LABELS = Set.of("HOME", "WORK");

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public AddressBook(MongoTemplate mongo, TransactionTemplate transaction, Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** The customer's addresses: the preferred one first, then Home, Work and the rest by name. */
    public List<SavedAddress> list(String userId) {
        return documents(userId).stream()
            .sorted(Comparator.comparing((AddressDocument a) -> !a.preferred())
                .thenComparing((AddressDocument a) -> order(a.label()))
                .thenComparing((AddressDocument a) -> (a.name() != null) ? a.name().toLowerCase(Locale.ROOT) : "")
                .thenComparing(AddressDocument::createdAt))
            .map(AddressBook::toView)
            .toList();
    }

    public SavedAddress get(String userId, String addressId) {
        return toView(require(userId, addressId));
    }

    public SavedAddress add(String userId, AddressInput input) {
        Clean clean = clean(input);
        Instant now = this.clock.instant();
        return this.transaction.execute((status) -> {
            List<AddressDocument> existing = documents(userId);
            if (existing.size() >= MAX_ADDRESSES) {
                throw new ModuleException.Conflict("You can keep up to " + MAX_ADDRESSES + " addresses", "ADDRESS_LIMIT");
            }
            requireLabelFree(existing, clean.label(), null);
            boolean preferred = input.preferred() || existing.isEmpty();
            if (preferred) {
                clearPreferred(userId, now);
            }
            AddressDocument saved = this.mongo.insert(new AddressDocument(Ids.newId(), userId, clean.label(), clean.name(),
                    input.lat(), input.lng(), clean.addressLine(), clean.instructions(), preferred, now, now, null));
            return toView(saved);
        });
    }

    /**
     * Replaces an address.
     *
     * @param version the version the customer edited, or {@code null} to skip the check
     */
    public SavedAddress update(String userId, String addressId, AddressInput input, Long version) {
        Clean clean = clean(input);
        Instant now = this.clock.instant();
        try {
            return this.transaction.execute((status) -> {
                AddressDocument current = require(userId, addressId);
                if (version != null && !version.equals(current.version())) {
                    throw stale();
                }
                requireLabelFree(documents(userId), clean.label(), addressId);
                boolean preferred = input.preferred() || current.preferred();
                if (preferred && !current.preferred()) {
                    clearPreferred(userId, now);
                }
                AddressDocument saved = this.mongo.save(new AddressDocument(current.id(), userId, clean.label(), clean.name(),
                        input.lat(), input.lng(), clean.addressLine(), clean.instructions(), preferred, current.createdAt(), now,
                        current.version()));
                return toView(saved);
            });
        }
        catch (OptimisticLockingFailureException ex) {
            throw stale();
        }
    }

    /** Removes an address; when it was preferred, the next one in the list becomes preferred. */
    public void remove(String userId, String addressId) {
        Instant now = this.clock.instant();
        this.transaction.executeWithoutResult((status) -> {
            AddressDocument current = require(userId, addressId);
            this.mongo.remove(byId(userId, addressId), AddressDocument.class);
            if (current.preferred()) {
                documents(userId).stream()
                    .min(Comparator.comparing((AddressDocument a) -> order(a.label())).thenComparing(AddressDocument::createdAt))
                    .ifPresent((next) -> this.mongo.save(next.withPreferred(true, now)));
            }
        });
    }

    private List<AddressDocument> documents(String userId) {
        return this.mongo.find(Query.query(Criteria.where("userId").is(userId)), AddressDocument.class);
    }

    private AddressDocument require(String userId, String addressId) {
        AddressDocument found = this.mongo.findOne(byId(userId, addressId), AddressDocument.class);
        if (found == null) {
            throw new ModuleException.NotFound("No such address", "ADDRESS_NOT_FOUND");
        }
        return found;
    }

    private void clearPreferred(String userId, Instant now) {
        this.mongo.updateMulti(Query.query(Criteria.where("userId").is(userId).and("preferred").is(true)),
                new Update().set("preferred", false).set("updatedAt", now).inc("version", 1), AddressDocument.class);
    }

    private static Query byId(String userId, String addressId) {
        return Query.query(Criteria.where("userId").is(userId).and("_id").is(addressId));
    }

    private static void requireLabelFree(List<AddressDocument> existing, String label, String exceptId) {
        if (!SINGLE_LABELS.contains(label)) {
            return;
        }
        boolean taken = existing.stream().anyMatch((a) -> label.equals(a.label()) && !a.id().equals(exceptId));
        if (taken) {
            throw new ModuleException.Conflict("You already have a " + label.toLowerCase(Locale.ROOT) + " address",
                    "ADDRESS_LABEL_TAKEN", java.util.Map.of("label", label));
        }
    }

    private static int order(String label) {
        return switch (label) {
            case "HOME" -> 0;
            case "WORK" -> 1;
            default -> 2;
        };
    }

    private record Clean(String label, String name, String addressLine, String instructions) {
    }

    private static Clean clean(AddressInput input) {
        String label = (input.label() != null) ? input.label().trim().toUpperCase(Locale.ROOT) : "";
        if (!LABELS.contains(label)) {
            throw new ModuleException.BadRequest("Choose Home, Work or Other", "ADDRESS_LABEL_INVALID");
        }
        if (!Double.isFinite(input.lat()) || !Double.isFinite(input.lng()) || Math.abs(input.lat()) > 90
                || Math.abs(input.lng()) > 180 || (input.lat() == 0 && input.lng() == 0)) {
            throw new ModuleException.BadRequest("Put the pin on the map", "LOCATION_INVALID");
        }
        String line = trim(input.addressLine(), 200);
        if (line == null) {
            throw new ModuleException.BadRequest("Write the street and house", "ADDRESS_LINE_REQUIRED");
        }
        String name = "OTHER".equals(label) ? trim(input.name(), 40) : null;
        return new Clean(label, name, line, trim(input.instructions(), 300));
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip();
        return (clean.length() > max) ? clean.substring(0, max) : clean;
    }

    private static ModuleException stale() {
        return new ModuleException.Conflict("The address changed meanwhile; reload it", "ADDRESS_CHANGED");
    }

    static SavedAddress toView(AddressDocument a) {
        return new SavedAddress(a.id(), a.label(), a.name(), a.lat(), a.lng(), a.addressLine(), a.instructions(),
                a.preferred(), a.updatedAt(), (a.version() != null) ? a.version() : 0);
    }
}
