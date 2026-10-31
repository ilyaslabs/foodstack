package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /api/v1/zones/stores/{storeId}}: every zone of the branch, first match wins.
 *
 * @param zones the zones in priority order
 */
public record ZonesRequest(@NotNull List<@Valid ZoneRequest> zones) {
}
