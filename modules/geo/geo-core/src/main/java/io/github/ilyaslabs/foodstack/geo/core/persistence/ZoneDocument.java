package io.github.ilyaslabs.foodstack.geo.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A delivery zone of a self-delivery branch in {@code geo_zones} (unsharded: few, small, rarely
 * written). A ring zone keeps its centre and radius so the editor can show it again.
 */
@Document("geo_zones")
public class ZoneDocument {

    @Id
    private String id;

    private String storeId;

    private String name;

    private int sortOrder;

    private GeoJsonPolygon area;

    private Double centerLat;

    private Double centerLng;

    private Integer radiusMeters;

    private long chargeMinor;

    private long minOrderMinor;

    private Long freeDeliveryAboveMinor;

    private Instant updatedAt;

    private String updatedBy;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStoreId() {
        return this.storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return this.sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public GeoJsonPolygon getArea() {
        return this.area;
    }

    public void setArea(GeoJsonPolygon area) {
        this.area = area;
    }

    public Double getCenterLat() {
        return this.centerLat;
    }

    public void setCenterLat(Double centerLat) {
        this.centerLat = centerLat;
    }

    public Double getCenterLng() {
        return this.centerLng;
    }

    public void setCenterLng(Double centerLng) {
        this.centerLng = centerLng;
    }

    public Integer getRadiusMeters() {
        return this.radiusMeters;
    }

    public void setRadiusMeters(Integer radiusMeters) {
        this.radiusMeters = radiusMeters;
    }

    public long getChargeMinor() {
        return this.chargeMinor;
    }

    public void setChargeMinor(long chargeMinor) {
        this.chargeMinor = chargeMinor;
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
}
