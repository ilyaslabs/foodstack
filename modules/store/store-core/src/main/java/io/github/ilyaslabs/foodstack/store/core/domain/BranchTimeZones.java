package io.github.ilyaslabs.foodstack.store.core.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.iakovlev.timeshape.TimeZoneEngine;

/**
 * Finds a branch's time zone from where it is, offline (TDD 7.5, gap 11). A deployment serves one
 * country, so only that country's zones are candidates: the country is the one of the deployment's
 * own time zone, from the IANA {@code zone.tab} shipped with this class. A country with one zone
 * needs no map at all. For a country with several, the zone boundaries of just those zones are
 * loaded (about a second and 20-30 MB, kept softly so a run of branch saves reuses them).
 */
public class BranchTimeZones {

    private final Map<String, String> countryOfZone = new HashMap<>();

    private final Map<String, Set<ZoneId>> zonesOfCountry = new HashMap<>();

    private SoftReference<Boundaries> boundaries = new SoftReference<>(null);

    public BranchTimeZones() {
        try (InputStream in = BranchTimeZones.class.getResourceAsStream("zone.tab")) {
            if (in == null) {
                throw new IllegalStateException("zone.tab is missing");
            }
            new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII)).lines()
                .filter((line) -> !line.isBlank() && !line.startsWith("#"))
                .map((line) -> line.split("\t"))
                .filter((columns) -> columns.length >= 3)
                .forEach((columns) -> {
                    this.countryOfZone.put(columns[2], columns[0]);
                    this.zonesOfCountry.computeIfAbsent(columns[0], (k) -> new LinkedHashSet<>()).add(ZoneId.of(columns[2]));
                });
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The time zone at a point, among the zones of the deployment's country.
     *
     * @param deploymentZone the deployment's own time zone, which names the country
     * @return the zone at the point, or the deployment's zone when the point is in none of them
     */
    public ZoneId zoneAt(double lat, double lng, ZoneId deploymentZone) {
        String country = this.countryOfZone.get(deploymentZone.getId());
        Set<ZoneId> candidates = (country != null) ? this.zonesOfCountry.get(country) : null;
        if (candidates == null) {
            return deploymentZone;
        }
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        return boundaries(country, candidates).query(lat, lng).orElse(deploymentZone);
    }

    private synchronized TimeZoneEngine boundaries(String country, Set<ZoneId> candidates) {
        Boundaries kept = this.boundaries.get();
        if (kept == null || !kept.country().equals(country)) {
            kept = new Boundaries(country, TimeZoneEngine.initialize(candidates, true));
            this.boundaries = new SoftReference<>(kept);
        }
        return kept.engine();
    }

    /** The IANA id when it names a zone, for checking what an owner typed. */
    public static Optional<ZoneId> parse(String id) {
        try {
            return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(ZoneId.of(id.trim()));
        }
        catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private record Boundaries(String country, TimeZoneEngine engine) {
    }

}
