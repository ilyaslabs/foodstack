package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A user in {@code identity_users}; shard key {@code { _id: hashed }} (read by id from the token).
 * The email is stored encrypted, with a keyed blind index for lookups (TDD 6.2); uniqueness of the
 * email lives in {@code identity_login_keys}, because a unique index here would have to start
 * with the shard key.
 */
@Document("identity_users")
public class UserDocument {

    @Id
    private String id;

    private String emailCipher;

    private String emailIndex;

    private String passwordHash;

    private String displayName;

    private Set<Role> roles = EnumSet.noneOf(Role.class);

    /** Brands the user owns: the {@code brandIds} claim (TDD 9.1). */
    private Set<String> brandIds = new java.util.TreeSet<>();

    /** Branches the user manages or works at: the {@code storeIds} claim. */
    private Set<String> storeIds = new java.util.TreeSet<>();

    private UserStatus status = UserStatus.ACTIVE;

    /** The {@code tv} claim of new tokens; raised to revoke all older tokens. */
    private long tokenVersion;

    private String createdBy;

    /** Who blocked the account, when and why (PRD ID-4); cleared when unblocked. */
    private String blockedBy;

    private Instant blockedAt;

    private String blockedReason;

    /** When the customer asked to delete the account, or {@code null} (PRD ID-7). */
    private Instant deletionRequestedAt;

    /** When a requested deletion runs; signing in before then clears it. */
    private Instant deleteAfter;

    private Instant deletedAt;

    /** The authenticator secret, encrypted (PRD ID-5); {@code null} until two-step sign-in is on. */
    private String totpSecretCipher;

    /** A secret being set up, encrypted; it replaces {@link #totpSecretCipher} once a code from it is confirmed. */
    private String totpPendingCipher;

    private Instant totpEnabledAt;

    /**
     * When someone first proved they read this address (an emailed code, or a provider's verified
     * email); {@code null} until then. Sign-up does not prove it.
     */
    private Instant emailVerifiedAt;

    /** The time step of the last code used, so each code works once. */
    private Long totpLastStep;

    /** SHA-256 hashes of the unused recovery codes. */
    private List<String> recoveryCodeHashes;

    /** Social sign-ins linked to this account: provider to subject (PRD ID-2). */
    private Map<String, String> socialLogins;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmailCipher() {
        return this.emailCipher;
    }

    public void setEmailCipher(String emailCipher) {
        this.emailCipher = emailCipher;
    }

    public String getEmailIndex() {
        return this.emailIndex;
    }

    public void setEmailIndex(String emailIndex) {
        this.emailIndex = emailIndex;
    }

    public String getPasswordHash() {
        return this.passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Set<Role> getRoles() {
        return this.roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public UserStatus getStatus() {
        return this.status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public long getTokenVersion() {
        return this.tokenVersion;
    }

    public void setTokenVersion(long tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public String getCreatedBy() {
        return this.createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getTotpSecretCipher() {
        return this.totpSecretCipher;
    }

    public void setTotpSecretCipher(String totpSecretCipher) {
        this.totpSecretCipher = totpSecretCipher;
    }

    public String getTotpPendingCipher() {
        return this.totpPendingCipher;
    }

    public void setTotpPendingCipher(String totpPendingCipher) {
        this.totpPendingCipher = totpPendingCipher;
    }

    public Instant getEmailVerifiedAt() {
        return this.emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public Instant getTotpEnabledAt() {
        return this.totpEnabledAt;
    }

    public void setTotpEnabledAt(Instant totpEnabledAt) {
        this.totpEnabledAt = totpEnabledAt;
    }

    public long getTotpLastStep() {
        return (this.totpLastStep != null) ? this.totpLastStep : 0L;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }

    public List<String> getRecoveryCodeHashes() {
        return (this.recoveryCodeHashes != null) ? this.recoveryCodeHashes : List.of();
    }

    public void setRecoveryCodeHashes(List<String> recoveryCodeHashes) {
        this.recoveryCodeHashes = recoveryCodeHashes;
    }

    public Map<String, String> getSocialLogins() {
        return (this.socialLogins != null) ? this.socialLogins : Map.of();
    }

    public void setSocialLogins(Map<String, String> socialLogins) {
        this.socialLogins = socialLogins;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return this.version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Set<String> getBrandIds() {
        return this.brandIds;
    }

    public void setBrandIds(Set<String> brandIds) {
        this.brandIds = (brandIds != null) ? brandIds : new java.util.TreeSet<>();
    }

    public Set<String> getStoreIds() {
        return this.storeIds;
    }

    public void setStoreIds(Set<String> storeIds) {
        this.storeIds = (storeIds != null) ? storeIds : new java.util.TreeSet<>();
    }

    public String getBlockedBy() {
        return this.blockedBy;
    }

    public void setBlockedBy(String blockedBy) {
        this.blockedBy = blockedBy;
    }

    public Instant getBlockedAt() {
        return this.blockedAt;
    }

    public void setBlockedAt(Instant blockedAt) {
        this.blockedAt = blockedAt;
    }

    public String getBlockedReason() {
        return this.blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public Instant getDeletionRequestedAt() {
        return this.deletionRequestedAt;
    }

    public void setDeletionRequestedAt(Instant deletionRequestedAt) {
        this.deletionRequestedAt = deletionRequestedAt;
    }

    public Instant getDeleteAfter() {
        return this.deleteAfter;
    }

    public void setDeleteAfter(Instant deleteAfter) {
        this.deleteAfter = deleteAfter;
    }

    public Instant getDeletedAt() {
        return this.deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
