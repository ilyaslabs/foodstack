package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.store.core.application.ModifierGroup;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An item of a brand menu in {@code store_menu_items}; shard key {@code { brandId: hashed }}.
 * {@code branchIds} limits a branch-only item to chosen branches; absent means every branch (PRD BB-3).
 */
@Document("store_menu_items")
public class MenuItemDocument {

    @Id
    private String id;

    private String brandId;

    private String sectionId;

    private Map<String, String> name = new LinkedHashMap<>();

    private Map<String, String> description = new LinkedHashMap<>();

    private long priceMinor;

    private String imageMediaId;

    private Map<String, String> imageUrls = new LinkedHashMap<>();

    private List<String> dietaryTags = new ArrayList<>();

    private List<ModifierGroup> modifierGroups = new ArrayList<>();

    private Set<String> branchIds;

    private int sortOrder;

    /** Soft delete: past orders keep pointing at it. */
    private Instant deletedAt;

    private Instant createdAt;

    private String createdBy;

    private Instant updatedAt;

    private String updatedBy;

    @Version
    private Long version;


    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBrandId() {
        return this.brandId;
    }

    public void setBrandId(String brandId) {
        this.brandId = brandId;
    }

    public String getSectionId() {
        return this.sectionId;
    }

    public void setSectionId(String sectionId) {
        this.sectionId = sectionId;
    }

    public Map<String, String> getName() {
        return this.name;
    }

    public void setName(Map<String, String> name) {
        this.name = name;
    }

    public Map<String, String> getDescription() {
        return this.description;
    }

    public void setDescription(Map<String, String> description) {
        this.description = description;
    }

    public long getPriceMinor() {
        return this.priceMinor;
    }

    public void setPriceMinor(long priceMinor) {
        this.priceMinor = priceMinor;
    }

    public String getImageMediaId() {
        return this.imageMediaId;
    }

    public void setImageMediaId(String imageMediaId) {
        this.imageMediaId = imageMediaId;
    }

    public Map<String, String> getImageUrls() {
        return this.imageUrls;
    }

    public void setImageUrls(Map<String, String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public List<String> getDietaryTags() {
        return this.dietaryTags;
    }

    public void setDietaryTags(List<String> dietaryTags) {
        this.dietaryTags = dietaryTags;
    }

    public List<ModifierGroup> getModifierGroups() {
        return this.modifierGroups;
    }

    public void setModifierGroups(List<ModifierGroup> modifierGroups) {
        this.modifierGroups = modifierGroups;
    }

    public Set<String> getBranchIds() {
        return this.branchIds;
    }

    public void setBranchIds(Set<String> branchIds) {
        this.branchIds = branchIds;
    }

    public int getSortOrder() {
        return this.sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getDeletedAt() {
        return this.deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return this.updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getVersion() {
        return this.version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
