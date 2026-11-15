package io.github.ilyaslabs.foodstack.geo.core.persistence;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.geo.core.application.PeakWindow;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The operator's delivery pricing, document {@code operator-delivery} of {@code geo_settings}
 * (unsharded): base charge plus a rate per km of road distance, up to a maximum distance, with a
 * minimum order, a free-delivery threshold and dearer peak hours (PRD DR-1b).
 */
@Document("geo_settings")
public class OperatorPricingDocument {

    public static final String ID = "operator-delivery";

    @Id
    private String id = ID;

    /** Whether operator riders deliver at all; branches cannot choose operator delivery while off. */
    private boolean enabled;

    private long baseChargeMinor;

    private long perKmMinor;

    private int maxDistanceMeters;

    private long minOrderMinor;

    private Long freeDeliveryAboveMinor;

    /** 0 in documents written before peak hours existed; read as 100. */
    private int peakMultiplierPercent;

    private List<PeakWindow> peakWindows;

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

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getBaseChargeMinor() {
        return this.baseChargeMinor;
    }

    public void setBaseChargeMinor(long baseChargeMinor) {
        this.baseChargeMinor = baseChargeMinor;
    }

    public long getPerKmMinor() {
        return this.perKmMinor;
    }

    public void setPerKmMinor(long perKmMinor) {
        this.perKmMinor = perKmMinor;
    }

    public int getMaxDistanceMeters() {
        return this.maxDistanceMeters;
    }

    public void setMaxDistanceMeters(int maxDistanceMeters) {
        this.maxDistanceMeters = maxDistanceMeters;
    }

    public long getMinOrderMinor() {
        return this.minOrderMinor;
    }

    public void setMinOrderMinor(long minOrderMinor) {
        this.minOrderMinor = minOrderMinor;
    }

    public Long getFreeDeliveryAboveMinor() {
        return this.freeDeliveryAboveMinor;
    }

    public void setFreeDeliveryAboveMinor(Long freeDeliveryAboveMinor) {
        this.freeDeliveryAboveMinor = freeDeliveryAboveMinor;
    }

    public int getPeakMultiplierPercent() {
        return this.peakMultiplierPercent;
    }

    public void setPeakMultiplierPercent(int peakMultiplierPercent) {
        this.peakMultiplierPercent = peakMultiplierPercent;
    }

    public List<PeakWindow> getPeakWindows() {
        return this.peakWindows;
    }

    public void setPeakWindows(List<PeakWindow> peakWindows) {
        this.peakWindows = peakWindows;
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
