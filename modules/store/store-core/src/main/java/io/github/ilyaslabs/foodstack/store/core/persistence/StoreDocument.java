package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.core.application.Address;
import io.github.ilyaslabs.foodstack.store.core.application.Closure;
import io.github.ilyaslabs.foodstack.store.core.application.Commission;
import io.github.ilyaslabs.foodstack.store.core.application.CommissionChange;
import io.github.ilyaslabs.foodstack.store.core.application.PaymentCollection;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A branch in {@code store_stores}; shard key {@code { _id: hashed }} (a branch is read by its id).
 * Orders, zones, riders and payouts all stay keyed by this id (TDD 5.7).
 */
@Document("store_stores")
public class StoreDocument {

    @Id
    private String id;

    private String brandId;

    /** Copy of the brand name for back-office lists; kept in step by the brand service. */
    private String brandName;

    private String branchName;

    /** Web address of the branch ({@code /stores/<slug>}); claimed in {@code store_slugs} so it is unique. */
    private String slug;

    private String legalName;

    private String taxId;

    private Set<String> ownerUserIds = new TreeSet<>();

    private Address address;

    private GeoJsonPoint location;

    private Map<DayOfWeek, List<TimeRange>> openingHours = new EnumMap<>(DayOfWeek.class);

    private List<Closure> closures = new ArrayList<>();

    private boolean temporarilyClosed;

    private String closedReason;

    private Instant closedAt;

    private int prepMinutes;

    /** IANA zone of the hours; {@code null} on branches saved before it existed, which use the deployment's. */
    private String timezone;

    private DeliveryMode deliveryMode;

    /** Set by the operator: operator delivery may be chosen (PRD ST-13). */
    private boolean operatorDeliveryAllowed;

    private boolean pickupEnabled;

    /** Overrides the brand commission when set (PRD BB-8). */
    private Commission commissionOverride;

    /** {@code OPERATOR} or {@code DIRECT}: who collects card payments (PRD DP-2); the same as {@code collection.mode}. */
    private String paymentCollection = "OPERATOR";

    /** The direct-collection permission with its credit limit and reason, or {@code null} before it was ever set. */
    private PaymentCollection collection;

    /** The operator's city the branch is in (PRD DR-1, DR-1b), or {@code null}. */
    private String cityId;

    /** Width of the kitchen printer's paper roll: 80 (default) or 58 mm. */
    private Integer ticketPaperMm;

    /** Dated changes of the branch's own commission (PRD FI-2); a {@code null} commission goes back to the brand's. */
    private List<CommissionChange> commissionChanges = new ArrayList<>();

    private StoreStatus status;

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

    public String getBrandName() {
        return this.brandName;
    }

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }

    public String getSlug() {
        return this.slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getBranchName() {
        return this.branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getLegalName() {
        return this.legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getTaxId() {
        return this.taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public Set<String> getOwnerUserIds() {
        return this.ownerUserIds;
    }

    public void setOwnerUserIds(Set<String> ownerUserIds) {
        this.ownerUserIds = ownerUserIds;
    }

    public Address getAddress() {
        return this.address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public GeoJsonPoint getLocation() {
        return this.location;
    }

    public void setLocation(GeoJsonPoint location) {
        this.location = location;
    }

    public Map<DayOfWeek, List<TimeRange>> getOpeningHours() {
        return this.openingHours;
    }

    public void setOpeningHours(Map<DayOfWeek, List<TimeRange>> openingHours) {
        this.openingHours = openingHours;
    }

    public List<Closure> getClosures() {
        return this.closures;
    }

    public void setClosures(List<Closure> closures) {
        this.closures = closures;
    }

    public boolean isTemporarilyClosed() {
        return this.temporarilyClosed;
    }

    public void setTemporarilyClosed(boolean temporarilyClosed) {
        this.temporarilyClosed = temporarilyClosed;
    }

    public String getClosedReason() {
        return this.closedReason;
    }

    public void setClosedReason(String closedReason) {
        this.closedReason = closedReason;
    }

    public Instant getClosedAt() {
        return this.closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getTimezone() {
        return this.timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getPrepMinutes() {
        return this.prepMinutes;
    }

    public void setPrepMinutes(int prepMinutes) {
        this.prepMinutes = prepMinutes;
    }

    public DeliveryMode getDeliveryMode() {
        return this.deliveryMode;
    }

    public void setDeliveryMode(DeliveryMode deliveryMode) {
        this.deliveryMode = deliveryMode;
    }

    public boolean isOperatorDeliveryAllowed() {
        return this.operatorDeliveryAllowed;
    }

    public void setOperatorDeliveryAllowed(boolean operatorDeliveryAllowed) {
        this.operatorDeliveryAllowed = operatorDeliveryAllowed;
    }

    public boolean isPickupEnabled() {
        return this.pickupEnabled;
    }

    public void setPickupEnabled(boolean pickupEnabled) {
        this.pickupEnabled = pickupEnabled;
    }

    public Commission getCommissionOverride() {
        return this.commissionOverride;
    }

    public void setCommissionOverride(Commission commissionOverride) {
        this.commissionOverride = commissionOverride;
    }

    public String getPaymentCollection() {
        return this.paymentCollection;
    }

    public void setPaymentCollection(String paymentCollection) {
        this.paymentCollection = paymentCollection;
    }

    public PaymentCollection getCollection() {
        return this.collection;
    }

    public void setCollection(PaymentCollection collection) {
        this.collection = collection;
    }

    public StoreStatus getStatus() {
        return this.status;
    }

    public void setStatus(StoreStatus status) {
        this.status = status;
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

    public String getCityId() {
        return this.cityId;
    }

    public void setCityId(String cityId) {
        this.cityId = cityId;
    }

    public Integer getTicketPaperMm() {
        return this.ticketPaperMm;
    }

    public void setTicketPaperMm(Integer ticketPaperMm) {
        this.ticketPaperMm = ticketPaperMm;
    }

    public List<CommissionChange> getCommissionChanges() {
        return this.commissionChanges;
    }

    public void setCommissionChanges(List<CommissionChange> commissionChanges) {
        this.commissionChanges = commissionChanges;
    }
}
