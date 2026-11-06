package io.github.ilyaslabs.foodstack.geo.api;

import java.util.Map;

/**
 * A city the operator works in (PRD DR-1, DR-1b, PR-2). Branches and operator riders belong to
 * one; dispatch looks for riders of the branch's city and operator delivery may be priced per city.
 *
 * @param id city id
 * @param name name by language
 * @param centre middle of the city, used to suggest the city of a new branch
 * @param active whether new branches and riders can be put in it
 * @param ownPricing whether operator delivery in this city has its own pricing
 */
public record CityView(String id, Map<String, String> name, GeoPoint centre, boolean active, boolean ownPricing) {
}
