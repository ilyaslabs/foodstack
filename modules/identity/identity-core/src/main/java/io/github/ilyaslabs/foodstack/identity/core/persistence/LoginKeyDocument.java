package io.github.ilyaslabs.foodstack.identity.core.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Email blind index to user id in {@code identity_login_keys}; shard key {@code { loginKey: 1 }}
 * with a unique index on it, so each email belongs to at most one user.
 */
@Document("identity_login_keys")
public class LoginKeyDocument {

    @Id
    private String id;

    private String loginKey;

    private String userId;

    public LoginKeyDocument() {
    }

    public LoginKeyDocument(String id, String loginKey, String userId) {
        this.id = id;
        this.loginKey = loginKey;
        this.userId = userId;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLoginKey() {
        return this.loginKey;
    }

    public void setLoginKey(String loginKey) {
        this.loginKey = loginKey;
    }

    public String getUserId() {
        return this.userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
