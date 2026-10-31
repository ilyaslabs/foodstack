package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.IdentityPersistenceTestApplication;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest(classes = IdentityPersistenceTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class IdentityRepositoriesTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    UserRepository users;

    @Autowired
    LoginKeyRepository loginKeys;

    @Autowired
    MongoTemplate identityMongoTemplate;

    @Test
    @DisplayName("allows each email (blind index) to belong to one user only, on the sharded login-key collection")
    void uniqueLoginKeys() {
        String key = "index-" + Ids.newId();
        this.loginKeys.insert(new LoginKeyDocument(Ids.newId(), key, "user-1"));

        assertThatExceptionOfType(DuplicateKeyException.class)
            .isThrownBy(() -> this.loginKeys.insert(new LoginKeyDocument(Ids.newId(), key, "user-2")));
        assertThat(this.loginKeys.findByLoginKey(key)).get().extracting(LoginKeyDocument::getUserId).isEqualTo("user-1");
        assertThat(shardKey("identity_login_keys")).isEqualTo(new Document("loginKey", 1));
    }

    @Test
    @DisplayName("stores users sharded on hashed _id and lists back-office users newest first")
    void users() {
        UserDocument admin = this.users.insert(user(EnumSet.of(Role.ADMIN)));
        UserDocument support = this.users.insert(user(EnumSet.of(Role.SUPPORT)));
        this.users.insert(user(EnumSet.of(Role.CUSTOMER)));

        List<UserDocument> staff = this.users.findByRolesInOrderByIdDesc(List.of(Role.ADMIN, Role.SUPPORT), Limit.of(10));

        assertThat(staff).extracting(UserDocument::getId).containsSubsequence(support.getId(), admin.getId());
        assertThat(staff).noneMatch((u) -> u.getRoles().contains(Role.CUSTOMER));
        assertThat(this.users.existsByRolesContaining(Role.ADMIN)).isTrue();
        assertThat(shardKey("identity_users")).isEqualTo(new Document("_id", "hashed"));
    }

    private Document shardKey(String collection) {
        String db = this.identityMongoTemplate.getDb().getName();
        return this.identityMongoTemplate.getMongoDatabaseFactory().getMongoDatabase("config")
            .getCollection("collections")
            .find(new Document("_id", db + "." + collection))
            .first()
            .get("key", Document.class);
    }

    private static UserDocument user(EnumSet<Role> roles) {
        UserDocument user = new UserDocument();
        user.setId(Ids.newId());
        user.setEmailCipher("cipher");
        user.setEmailIndex("index-" + user.getId());
        user.setPasswordHash("{argon2}hash");
        user.setDisplayName("Someone");
        user.setRoles(roles);
        user.setTokenVersion(1);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
    }
}
