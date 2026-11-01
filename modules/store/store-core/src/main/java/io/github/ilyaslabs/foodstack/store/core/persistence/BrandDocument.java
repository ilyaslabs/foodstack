package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.ilyaslabs.foodstack.store.core.application.Commission;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A brand in {@code store_brands}; shard key {@code { _id: hashed }} (read by brand id). Holds what
 * its branches share: name, images, cuisines, the menu and the default commission (TDD 5.7).
 */
@Document("store_brands")
public class BrandDocument {

    @Id
    private String id;

    private String name;

    /** Translated text by language tag. */
    private Map<String, String> description = new LinkedHashMap<>();

    private List<String> cuisines = new ArrayList<>();

    private String logoMediaId;

    private Map<String, String> logoUrls = new LinkedHashMap<>();

    private String coverMediaId;

    private Map<String, String> coverUrls = new LinkedHashMap<>();

    private Set<String> ownerUserIds = new TreeSet<>();

    /** Default commission of every branch. */
    private Commission commission = new Commission(0, 0);

    private int branchCount;

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

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getDescription() {
        return this.description;
    }

    public void setDescription(Map<String, String> description) {
        this.description = description;
    }

    public List<String> getCuisines() {
        return this.cuisines;
    }

    public void setCuisines(List<String> cuisines) {
        this.cuisines = cuisines;
    }

    public String getLogoMediaId() {
        return this.logoMediaId;
    }

    public void setLogoMediaId(String logoMediaId) {
        this.logoMediaId = logoMediaId;
    }

    public Map<String, String> getLogoUrls() {
        return this.logoUrls;
    }

    public void setLogoUrls(Map<String, String> logoUrls) {
        this.logoUrls = logoUrls;
    }

    public String getCoverMediaId() {
        return this.coverMediaId;
    }

    public void setCoverMediaId(String coverMediaId) {
        this.coverMediaId = coverMediaId;
    }

    public Map<String, String> getCoverUrls() {
        return this.coverUrls;
    }

    public void setCoverUrls(Map<String, String> coverUrls) {
        this.coverUrls = coverUrls;
    }

    public Set<String> getOwnerUserIds() {
        return this.ownerUserIds;
    }

    public void setOwnerUserIds(Set<String> ownerUserIds) {
        this.ownerUserIds = ownerUserIds;
    }

    public Commission getCommission() {
        return this.commission;
    }

    public void setCommission(Commission commission) {
        this.commission = commission;
    }

    public int getBranchCount() {
        return this.branchCount;
    }

    public void setBranchCount(int branchCount) {
        this.branchCount = branchCount;
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
