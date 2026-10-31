package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.time.Instant;
import java.util.EnumSet;
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

    private UserStatus status = UserStatus.ACTIVE;

    /** The {@code tv} claim of new tokens; raised to revoke all older tokens. */
    private long tokenVersion;

    private String createdBy;

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
}
