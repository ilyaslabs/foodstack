package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.core.IdentityPersistenceTestApplication;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyRepository;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.TokenRevocations;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The Users page queries against a real MongoDB (lowest layer, real infrastructure). */
@SpringBootTest(classes = IdentityPersistenceTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class UserDirectoryTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    UserRepository users;

    @Autowired
    LoginKeyRepository loginKeys;

    @Autowired
    ModuleMongo identityMongo;

    private final TokenRevocations revocations = mock(TokenRevocations.class);

    private UserService service;

    private UserDirectory directory;

    @BeforeEach
    @SuppressWarnings("deprecation")
    void setUp() {
        this.identityMongo.template().remove(new org.springframework.data.mongodb.core.query.Query(), "identity_users");
        this.identityMongo.template().remove(new org.springframework.data.mongodb.core.query.Query(), "identity_login_keys");
        this.service = new UserService(this.users, this.loginKeys, new PiiCipher(Base64.getEncoder().encodeToString(new byte[32])),
                NoOpPasswordEncoder.getInstance(), mock(OutboxWriter.class), this.identityMongo.transactionTemplate(),
                this.revocations, Clock.systemUTC());
        this.directory = new UserDirectory(this.service, this.users, this.identityMongo.template(), Clock.systemUTC());
    }

    @Test
    @DisplayName("lists every account newest first in pages, and finds by full email, part of a name, role and status")
    void search() {
        UserAccount admin = this.service.create(new NewUser("admin@example.com", "password-123", "Ada Admin",
                EnumSet.of(Role.ADMIN)), null);
        UserAccount ali = this.service.register("ali@example.com", "password-123", "Ali Khan");
        UserAccount sara = this.service.register("sara@example.com", "password-123", "Sara Ali");

        UserDirectory.UserPage first = this.directory.search(null, null, null, null, 2);
        UserDirectory.UserPage second = this.directory.search("", null, null, first.nextCursor(), 2);

        assertThat(first.items()).extracting(UserAccount::id).containsExactly(sara.id(), ali.id());
        assertThat(second.items()).extracting(UserAccount::id).containsExactly(admin.id());
        assertThat(second.nextCursor()).isNull();
        assertThat(this.directory.search("ALI", null, null, null, 10).items()).extracting(UserAccount::id)
            .containsExactlyInAnyOrder(ali.id(), sara.id());
        assertThat(this.directory.search(" Ali@Example.com ", null, null, null, 10).items()).extracting(UserAccount::id)
            .containsExactly(ali.id());
        assertThat(this.directory.search("nobody@example.com", null, null, null, 10).items()).isEmpty();
        assertThat(this.directory.search("a.*", null, null, null, 10).items()).isEmpty();
        assertThat(this.directory.search(null, Role.ADMIN, null, null, 10).items()).extracting(UserAccount::email)
            .containsExactly("admin@example.com");
        assertThat(this.directory.search(null, null, UserStatus.BLOCKED, null, 10).items()).isEmpty();
    }

    @Test
    @DisplayName("blocking needs a reason, revokes every token, spares the last admin and the caller; unblocking restores")
    void block() {
        UserAccount admin = this.service.create(new NewUser("admin@example.com", "password-123", "Ada Admin",
                EnumSet.of(Role.ADMIN)), null);
        UserAccount customer = this.service.register("ali@example.com", "password-123", "Ali Khan");

        assertThatThrownBy(() -> this.directory.block(customer.id(), " ", admin.id()))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class, (ex) -> assertThat(ex.code()).isEqualTo("BLOCK_REASON_REQUIRED"));
        assertThatThrownBy(() -> this.directory.block(admin.id(), "test", admin.id()))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("CANNOT_BLOCK_SELF"));
        assertThatThrownBy(() -> this.directory.block(admin.id(), "test", customer.id()))
            .isInstanceOfSatisfying(ModuleException.Conflict.class, (ex) -> assertThat(ex.code()).isEqualTo("LAST_ADMIN"));

        UserAccount blocked = this.directory.block(customer.id(), "Fake orders", admin.id());

        assertThat(blocked.status()).isEqualTo(UserStatus.BLOCKED);
        verify(this.revocations).revokeBefore(eq(customer.id()), anyLong());
        assertThat(this.users.findById(customer.id()).orElseThrow().getBlockedReason()).isEqualTo("Fake orders");
        assertThat(this.directory.search(null, null, UserStatus.BLOCKED, null, 10).items()).extracting(UserAccount::id)
            .containsExactly(customer.id());
        assertThatThrownBy(() -> this.service.tokenClaims(customer.id())).isInstanceOf(ModuleException.Forbidden.class);

        assertThat(this.directory.unblock(customer.id()).status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(this.users.findById(customer.id()).orElseThrow().getBlockedReason()).isNull();
        assertThat(this.service.tokenClaims(customer.id()).roles()).isEqualTo(java.util.Set.copyOf(List.of(Role.CUSTOMER)));
    }
}
