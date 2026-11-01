package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;

/**
 * Every zone of a branch, in priority order.
 *
 * @param storeId the branch
 * @param zones its zones
 */
public record ZonesResponse(String storeId, List<ZoneResponse> zones) {
}
