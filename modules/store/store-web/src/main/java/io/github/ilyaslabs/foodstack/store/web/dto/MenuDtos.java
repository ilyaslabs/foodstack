package io.github.ilyaslabs.foodstack.store.web.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Public REST shapes of menus. Translated text is a map of language tag to text. */
public final class MenuDtos {

    private MenuDtos() {
    }

    /** {@code POST} or {@code PUT} a section; {@code version} is the one the editor saw (PUT only). */
    public record SectionRequest(@NotNull Map<String, String> name, @Min(0) Integer sortOrder, Long version) {
    }

    /** A choice in a modifier group; omit {@code id} for a new option. */
    public record OptionDto(String id, @NotNull Map<String, String> name, @Min(0) long priceDeltaMinor) {
    }

    /** A modifier group; omit {@code id} for a new group. */
    public record GroupDto(String id, @NotNull Map<String, String> name, @Min(0) int minSelect, @Min(1) int maxSelect,
            @NotNull List<@Valid OptionDto> options) {
    }

    /** {@code POST} or {@code PUT} an item; {@code version} is the one the editor saw (PUT only). */
    public record ItemRequest(@NotBlank String sectionId, @NotNull Map<String, String> name, Map<String, String> description,
            @Min(0) long priceMinor, String imageMediaId, List<String> dietaryTags, List<@Valid GroupDto> modifierGroups,
            Set<String> branchIds, @Min(0) Integer sortOrder, Long version) {
    }

    /** An item as the editor sees it. */
    public record ItemResponse(String id, String sectionId, Map<String, String> name, Map<String, String> description,
            long priceMinor, String imageMediaId, Map<String, String> imageUrls, List<String> dietaryTags,
            List<GroupDto> modifierGroups, Set<String> branchIds, int sortOrder, long version) {
    }

    /** A section as the editor sees it. */
    public record SectionResponse(String id, Map<String, String> name, int sortOrder, long version, List<ItemResponse> items) {
    }

    /** A brand menu as the editor sees it. */
    public record EditorMenuResponse(String brandId, String currency, List<String> languages, List<SectionResponse> sections) {
    }

    /** {@code PUT /api/v1/stores/{id}/menu/items/{itemId}/sold-out}. */
    public record SoldOutRequest(boolean soldOut, String optionId) {
    }

    /** A branch menu as customers see it. */
    public record MenuResponse(String storeId, String brandId, String currency, List<MenuSection> sections) {
    }

    public record MenuSection(String id, Map<String, String> name, List<MenuItem> items) {
    }

    public record MenuItem(String id, Map<String, String> name, Map<String, String> description, long priceMinor,
            Map<String, String> imageUrls, List<String> dietaryTags, boolean soldOut, List<MenuGroup> modifierGroups) {
    }

    public record MenuGroup(String id, Map<String, String> name, int minSelect, int maxSelect, List<MenuOption> options) {
    }

    public record MenuOption(String id, Map<String, String> name, long priceDeltaMinor, boolean soldOut) {
    }
}
