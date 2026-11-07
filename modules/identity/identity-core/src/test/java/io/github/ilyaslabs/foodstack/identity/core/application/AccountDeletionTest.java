package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.core.IdentityPersistenceTestApplication;
import io.github.ilyaslabs.foodstack.identity.core.persistence.AddressDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.RefreshTokenDocument;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.TokenRevocations;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Account deletion against a real two-shard MongoDB (lowest layer, TDD 15.3). */
@SpringBootTest(classes = IdentityPersistenceTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class AccountDeletionTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo identityMongo;

    private final UserService users = mock(UserService.class);

    private final TokenRevocations revocations = mock(TokenRevocations.class);

    private OutboxWriter outbox;

    private Instant now = NOW;

    private AccountDeletion deletion;

    @BeforeEach
    void setUp() {
        for (String collection : List.of("identity_users", "identity_login_keys", "identity_addresses",
                "identity_refresh_tokens")) {
            this.identityMongo.template().remove(new Query(), collection);
        }
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return AccountDeletionTest.this.now;
            }
        };
        this.outbox = new OutboxWriter(this.identityMongo, JsonMapper.builder().build(), clock);
        this.identityMongo.template().remove(new Query(), this.outbox.collection());
        this.deletion = new AccountDeletion(this.identityMongo.template(), this.identityMongo.transactionTemplate(),
                this.outbox, this.users, this.revocations, Duration.ofDays(14), clock);
    }

    private void customer(String id, Role... roles) {
        UserDocument user = new UserDocument();
        user.setId(id);
        user.setEmailCipher("cipher-" + id);
        user.setEmailIndex("index-" + id);
        user.setPasswordHash("{argon2}hash");
        user.setDisplayName("Ayesha Khan");
        user.setRoles((roles.length == 0) ? EnumSet.of(Role.CUSTOMER) : EnumSet.of(roles[0], roles));
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(3);
        user.setCreatedAt(NOW.minus(Duration.ofDays(100)));
        user.setUpdatedAt(NOW.minus(Duration.ofDays(100)));
        this.identityMongo.template().insert(user);
        this.identityMongo.template().insert(new LoginKeyDocument("lk-" + id, "index-" + id, id));
        this.identityMongo.template().insert(new AddressDocument("a-" + id, id, "HOME", null, 31.5, 74.3, "12 Main Boulevard",
                null, true, NOW, NOW, null));
        this.identityMongo.template().insert(new RefreshTokenDocument("rt-" + id, "family", id, "WEB", 3, NOW,
                NOW.plus(Duration.ofDays(30)), null));
    }

    private UserDocument user(String id) {
        return this.identityMongo.template().findById(id, UserDocument.class);
    }

    private List<Document> events(String type) {
        return this.identityMongo.template()
            .getCollection(this.outbox.collection())
            .find(new Document("type", type))
            .into(new ArrayList<>());
    }

    @Test
    @DisplayName("a request schedules deletion after the grace period, signs out everywhere and is idempotent")
    void request() {
        // given
        customer("u1");

        // when
        Instant deleteAfter = this.deletion.request("u1");
        this.now = NOW.plusSeconds(60);
        Instant again = this.deletion.request("u1");

        // then
        assertThat(deleteAfter).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(again).isEqualTo(deleteAfter);
        assertThat(user("u1").getDeleteAfter()).isEqualTo(deleteAfter);
        assertThat(user("u1").getDeletionRequestedAt()).isEqualTo(NOW);
        assertThat(events("foodstack.identity.AccountDeletionScheduled.v1")).hasSize(1);
        verify(this.users).revokeTokens("u1", "DELETION_REQUESTED");
    }

    @Test
    @DisplayName("back-office accounts cannot delete themselves")
    void staff() {
        // given
        customer("s1", Role.CUSTOMER, Role.STORE_STAFF);

        // when / then
        assertThatThrownBy(() -> this.deletion.request("s1")).isInstanceOfSatisfying(ModuleException.class,
                (ex) -> assertThat(ex.code()).isEqualTo("ACCOUNT_HAS_STAFF_ROLES"));
        assertThat(user("s1").getDeleteAfter()).isNull();
    }

    @Test
    @DisplayName("nothing is deleted before the date; after it the account keeps only its id and UserDeleted goes out")
    void runDue() {
        // given
        customer("u1");
        customer("u2");
        this.deletion.request("u1");

        // when
        this.now = NOW.plus(Duration.ofDays(13));
        int early = this.deletion.runDue(100);
        this.now = NOW.plus(Duration.ofDays(14)).plusSeconds(1);
        int due = this.deletion.runDue(100);
        int twice = this.deletion.runDue(100);

        // then
        assertThat(early).isZero();
        assertThat(due).isEqualTo(1);
        assertThat(twice).isZero();
        UserDocument deleted = user("u1");
        assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(deleted.getEmailCipher()).isNull();
        assertThat(deleted.getEmailIndex()).isNull();
        assertThat(deleted.getPasswordHash()).isNull();
        assertThat(deleted.getDisplayName()).isNull();
        assertThat(deleted.getRoles()).isEmpty();
        assertThat(deleted.getDeleteAfter()).isNull();
        assertThat(deleted.getTokenVersion()).isEqualTo(4);
        var mongo = this.identityMongo.template();
        assertThat(mongo.find(Query.query(Criteria.where("userId").is("u1")), LoginKeyDocument.class)).isEmpty();
        assertThat(mongo.find(Query.query(Criteria.where("userId").is("u1")), AddressDocument.class)).isEmpty();
        assertThat(mongo.find(Query.query(Criteria.where("userId").is("u1")), RefreshTokenDocument.class)).isEmpty();
        assertThat(mongo.find(Query.query(Criteria.where("userId").is("u2")), AddressDocument.class)).hasSize(1);
        assertThat(user("u2").getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(events("foodstack.identity.UserDeleted.v1")).singleElement()
            .satisfies((event) -> assertThat(event.getString("subject")).isEqualTo("u1"));
        verify(this.revocations).revokeBefore("u1", 4);
    }
}
