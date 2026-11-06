package io.github.ilyaslabs.foodstack.store.web;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.foodstack.platform.web.ETags;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.core.application.Address;
import io.github.ilyaslabs.foodstack.store.core.application.BrandDetails;
import io.github.ilyaslabs.foodstack.store.core.application.Closure;
import io.github.ilyaslabs.foodstack.store.core.application.Commission;
import io.github.ilyaslabs.foodstack.store.core.application.CommissionChange;
import io.github.ilyaslabs.foodstack.store.core.application.DeliveryQuote;
import io.github.ilyaslabs.foodstack.store.core.application.EffectiveMenu;
import io.github.ilyaslabs.foodstack.store.core.application.MenuDetails;
import io.github.ilyaslabs.foodstack.store.core.application.MenuItemInput;
import io.github.ilyaslabs.foodstack.store.core.application.ModifierGroup;
import io.github.ilyaslabs.foodstack.store.core.application.ModifierOption;
import io.github.ilyaslabs.foodstack.store.core.application.OwnerDetails;
import io.github.ilyaslabs.foodstack.store.core.application.StoreDetails;
import io.github.ilyaslabs.foodstack.store.core.application.StoreSummary;
import io.github.ilyaslabs.foodstack.store.web.dto.MenuDtos;
import io.github.ilyaslabs.foodstack.store.web.dto.StoreDtos;
import io.github.ilyaslabs.moduleswitch.ModuleException;

/** Maps between REST shapes and the store services. */
final class StoreMapper {

    private StoreMapper() {
    }

    static long requireIfMatch(String resource, String ifMatch) {
        Long version = ETags.versionFromIfMatch(resource, ifMatch);
        if (version == null) {
            throw new ModuleException.BadRequest("Send If-Match with the ETag you edited, so changes are not lost",
                    "IF_MATCH_REQUIRED");
        }
        return version;
    }

    /** The version an editor saw, required to update menu entries. */
    static long requireVersion(Long version) {
        if (version == null) {
            throw new ModuleException.BadRequest("Send the version you edited, so changes are not lost", "VERSION_REQUIRED");
        }
        return version;
    }

    static GeoPoint point(StoreDtos.LatLng latLng) {
        return (latLng != null) ? new GeoPoint(latLng.lat(), latLng.lng()) : null;
    }

    static StoreDtos.LatLng latLng(GeoPoint point) {
        return (point != null) ? new StoreDtos.LatLng(point.lat(), point.lng()) : null;
    }

    static Address address(StoreDtos.AddressDto dto) {
        return (dto != null) ? new Address(dto.line1(), dto.area(), dto.city(), dto.postalCode()) : null;
    }

    static StoreDtos.AddressDto address(Address address) {
        return (address != null)
                ? new StoreDtos.AddressDto(address.line1(), address.area(), address.city(), address.postalCode()) : null;
    }

    static Commission commission(StoreDtos.CommissionDto dto) {
        return (dto != null) ? new Commission(dto.percentBasisPoints(), dto.fixedMinor()) : null;
    }

    static StoreDtos.CommissionDto commission(Commission commission) {
        return (commission != null) ? new StoreDtos.CommissionDto(commission.percentBasisPoints(), commission.fixedMinor())
                : null;
    }

    static DeliveryMode deliveryMode(String value) {
        try {
            return DeliveryMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
        catch (RuntimeException ex) {
            throw new ModuleException.BadRequest("deliveryMode must be SELF or OPERATOR", "STORE_INVALID");
        }
    }

    static StoreStatus status(String value) {
        try {
            return StoreStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
        catch (RuntimeException ex) {
            throw new ModuleException.BadRequest("status must be DRAFT, LIVE or PAUSED", "STORE_INVALID");
        }
    }

    static Map<DayOfWeek, List<TimeRange>> hours(Map<String, List<StoreDtos.TimeRangeDto>> dto) {
        Map<DayOfWeek, List<TimeRange>> hours = new EnumMap<>(DayOfWeek.class);
        dto.forEach((day, ranges) -> {
            DayOfWeek weekday;
            try {
                weekday = DayOfWeek.valueOf(day.trim().toUpperCase(java.util.Locale.ROOT));
            }
            catch (RuntimeException ex) {
                throw new ModuleException.BadRequest("openingHours keys must be MONDAY to SUNDAY", "HOURS_INVALID");
            }
            hours.put(weekday, (ranges != null)
                    ? ranges.stream().map((r) -> new TimeRange(r.open(), r.close())).toList() : List.of());
        });
        return hours;
    }

    static Map<String, List<StoreDtos.TimeRangeDto>> hourDtos(Map<DayOfWeek, List<TimeRange>> hours) {
        Map<String, List<StoreDtos.TimeRangeDto>> dto = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            List<TimeRange> ranges = (hours != null) ? hours.getOrDefault(day, List.of()) : List.of();
            dto.put(day.name(), ranges.stream().map((r) -> new StoreDtos.TimeRangeDto(r.open(), r.close())).toList());
        }
        return dto;
    }

    static List<Closure> closures(List<StoreDtos.ClosureDto> dto) {
        if (dto == null) {
            return List.of();
        }
        List<Closure> closures = new ArrayList<>();
        for (StoreDtos.ClosureDto closure : dto) {
            try {
                closures.add(new Closure(LocalDate.parse(closure.from()), LocalDate.parse(closure.to()), closure.reason()));
            }
            catch (DateTimeParseException ex) {
                throw new ModuleException.BadRequest("closure dates must be YYYY-MM-DD", "HOURS_INVALID");
            }
        }
        return closures;
    }

    static List<StoreDtos.ClosureDto> closureDtos(List<Closure> closures) {
        return (closures != null) ? closures.stream()
            .map((c) -> new StoreDtos.ClosureDto(c.from().toString(), c.to().toString(), c.reason()))
            .toList() : List.of();
    }

    static StoreDtos.BrandResponse brand(BrandDetails b) {
        StoreDtos.ImageDto logo = (b.logoMediaId() != null) ? new StoreDtos.ImageDto(b.logoMediaId(), b.logoUrls()) : null;
        StoreDtos.ImageDto cover = (b.coverMediaId() != null) ? new StoreDtos.ImageDto(b.coverMediaId(), b.coverUrls())
                : null;
        return new StoreDtos.BrandResponse(b.id(), b.name(), b.description(), b.cuisines(), logo, cover,
                commission(b.commission()), b.branchCount(), b.version(), EpochSeconds.of(b.updatedAt()),
                changes(b.commissionChanges()), b.ownerUserIds(), b.branchPromotionsAllowed());
    }

    static List<StoreDtos.CommissionChangeResponse> changes(List<CommissionChange> changes) {
        return changes.stream()
            .map((c) -> new StoreDtos.CommissionChangeResponse(commission(c.commission()), c.effectiveFrom().getEpochSecond(),
                    c.setBy(), EpochSeconds.of(c.setAt())))
            .toList();
    }

    static String displayName(String brandName, String branchName) {
        return (branchName == null || branchName.isBlank()) ? brandName : brandName + " - " + branchName;
    }

    static StoreDtos.StoreResponse store(StoreDetails s) {
        return new StoreDtos.StoreResponse(s.id(), s.slug(), brand(s.brand()), s.branchName(),
                displayName(s.brand().name(), s.branchName()), s.legalName(), s.taxId(), s.ownerUserIds(),
                address(s.address()), latLng(s.location()), hourDtos(s.openingHours()), closureDtos(s.closures()),
                s.temporarilyClosed(), s.closedReason(), s.prepMinutes(), s.deliveryMode().name(),
                s.operatorDeliveryAllowed(), s.pickupEnabled(), commission(s.commission()), s.status().name(), s.timezone(),
                s.openNow(), s.version(), EpochSeconds.of(s.createdAt()), EpochSeconds.of(s.updatedAt()), s.cityId(),
                s.ticketPaperMm(), changes(s.commissionChanges()), s.brandOwnerUserIds());
    }

    static StoreDtos.StorePageResponse page(StoreDetails s) {
        BrandDetails b = s.brand();
        String address = (s.address() != null) ? String.join(", ", java.util.stream.Stream
            .of(s.address().line1(), s.address().area(), s.address().city())
            .filter((part) -> part != null && !part.isBlank())
            .toList()) : null;
        return new StoreDtos.StorePageResponse(s.id(), s.slug(), b.id(), b.name(), s.branchName(), displayName(b.name(), s.branchName()),
                b.description(), b.cuisines(), b.logoUrls(), b.coverUrls(), address, latLng(s.location()), s.openNow(),
                s.temporarilyClosed(), s.closedReason(), hourDtos(s.openingHours()), s.prepMinutes(), s.deliveryMode().name(),
                s.pickupEnabled(), s.timezone());
    }

    static StoreDtos.StoreSummaryResponse summary(StoreSummary s) {
        return new StoreDtos.StoreSummaryResponse(s.id(), s.brandId(), s.brandName(), s.branchName(), s.city(),
                s.status().name(), s.temporarilyClosed(), s.deliveryMode().name(), EpochSeconds.of(s.createdAt()),
                s.logoUrls(), s.cityId(), s.branchCount());
    }

    static StoreDtos.OwnerResponse owner(OwnerDetails o) {
        return new StoreDtos.OwnerResponse(o.userId(), o.email(), o.displayName());
    }

    static StoreDtos.QuoteResponse quote(DeliveryQuote q) {
        return new StoreDtos.QuoteResponse(q.deliverable(), q.reason(), q.chargeMinor(), q.minOrderMinor(),
                q.freeDeliveryAboveMinor(), q.distanceMeters(), q.etaMinutes(), q.currency());
    }

    // ---- Menus ----------------------------------------------------------------------------

    static MenuItemInput item(MenuDtos.ItemRequest r) {
        List<ModifierGroup> groups = (r.modifierGroups() != null) ? r.modifierGroups()
            .stream()
            .map((g) -> new ModifierGroup(g.id(), g.name(), g.minSelect(), g.maxSelect(),
                    g.options().stream().map((o) -> new ModifierOption(o.id(), o.name(), o.priceDeltaMinor())).toList()))
            .toList() : List.of();
        return new MenuItemInput(r.sectionId(), r.name(), r.description(), r.priceMinor(), r.imageMediaId(),
                r.dietaryTags(), groups, r.branchIds(), (r.sortOrder() != null) ? r.sortOrder() : 0);
    }

    static MenuDtos.ItemResponse item(MenuDetails.Item i) {
        List<MenuDtos.GroupDto> groups = (i.modifierGroups() != null) ? i.modifierGroups()
            .stream()
            .map((g) -> new MenuDtos.GroupDto(g.id(), g.name(), g.minSelect(), g.maxSelect(), g.options()
                .stream()
                .map((o) -> new MenuDtos.OptionDto(o.id(), o.name(), o.priceDeltaMinor()))
                .toList()))
            .toList() : List.of();
        return new MenuDtos.ItemResponse(i.id(), i.sectionId(), i.name(), i.description(), i.priceMinor(),
                i.imageMediaId(), i.imageUrls(), i.dietaryTags(), groups, i.branchIds(), i.sortOrder(), i.version());
    }

    static MenuDtos.SectionResponse section(MenuDetails.Section s) {
        return new MenuDtos.SectionResponse(s.id(), s.name(), s.sortOrder(), s.version(),
                s.items().stream().map(StoreMapper::item).toList());
    }

    static MenuDtos.EditorMenuResponse editorMenu(MenuDetails m) {
        return new MenuDtos.EditorMenuResponse(m.brandId(), m.currency(), m.languages(),
                m.sections().stream().map(StoreMapper::section).toList());
    }

    static MenuDtos.MenuResponse menu(EffectiveMenu m) {
        return new MenuDtos.MenuResponse(m.storeId(), m.brandId(), m.currency(), m.sections()
            .stream()
            .map((s) -> new MenuDtos.MenuSection(s.id(), s.name(), s.items()
                .stream()
                .map((i) -> new MenuDtos.MenuItem(i.id(), i.name(), i.description(), i.priceMinor(), i.imageUrls(),
                        i.dietaryTags(), i.soldOut(), i.modifierGroups()
                            .stream()
                            .map((g) -> new MenuDtos.MenuGroup(g.id(), g.name(), g.minSelect(), g.maxSelect(), g.options()
                                .stream()
                                .map((o) -> new MenuDtos.MenuOption(o.id(), o.name(), o.priceDeltaMinor(), o.soldOut()))
                                .toList()))
                            .toList()))
                .toList()))
            .toList());
    }
}
