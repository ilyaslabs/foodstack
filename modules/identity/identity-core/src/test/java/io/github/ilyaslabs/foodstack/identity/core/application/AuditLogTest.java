package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.IdentityPersistenceTestApplication;
import io.github.ilyaslabs.foodstack.identity.core.domain.Permissions;
import io.github.ilyaslabs.foodstack.identity.core.persistence.AuditLogDocument;
import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The audit log against a real two-shard MongoDB (lowest layer, TDD 15.3). */
@SpringBootTest(classes = IdentityPersistenceTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class AuditLogTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo identityMongo;

    private final UserService users = mock(UserService.class);

    private AuditLog log;

    @BeforeEach
    void setUp() {
        this.identityMongo.template().remove(new Query(), AuditLogDocument.class);
        this.log = new AuditLog(this.identityMongo.template(), this.users,
                Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC));
        given(this.users.get("admin-1")).willReturn(new UserAccount("admin-1", "admin@example.com", "Demo Admin",
                Set.of(Role.ADMIN), Set.of(), null, null, Set.of(), Set.of()));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin-1", "p",
                Permissions.AUDIT_READ));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("keeps each write with the state before it (the previous write of the same path) and the user's name; a repeat is kept once")
    void beforeAndAfter() {
        AuditEntry first = write("/api/v1/stores/s1", "{\"name\":\"Spice\",\"version\":1}", 0);
        AuditEntry second = write("/api/v1/stores/s1", "{\"name\":\"Spice Route\",\"version\":2}", 1);
        AuditEntry other = write("/api/v1/stores/s2", "{\"name\":\"Napoli\",\"version\":1}", 2);
        this.log.record(first);
        this.log.record(second);
        this.log.record(second);
        this.log.record(other);

        List<AuditLogDocument> lines = this.log.search(null, null, null, null, 50);
        assertThat(lines).extracting(AuditLogDocument::id).containsExactly(other.id(), second.id(), first.id());
        assertThat(lines.get(1).before()).isEqualTo(first.after());
        assertThat(lines.get(1).after()).isEqualTo(second.after());
        assertThat(lines.get(1).actorName()).isEqualTo("Demo Admin");
        assertThat(lines.get(2).before()).isNull();
        assertThat(lines.get(0).before()).isNull();
    }

    @Test
    @DisplayName("records back-office sign-ins and refusals, not customers'; searches by user, kind and text, page by page")
    void signInsAndSearch() {
        this.log.signedIn("admin-1", "Demo Admin", Set.of(Role.ADMIN), "password", "10.0.0.1");
        this.log.signInFailed("owner-1", "Napoli Owner", Set.of(Role.STORE_OWNER), "password", "10.0.0.2");
        this.log.signedIn("customer-1", "Sara", Set.of(Role.CUSTOMER), "password", "10.0.0.3");
        this.log.record(write("/api/v1/promotions/p1", "{\"code\":\"WELCOME\"}", 0));

        assertThat(this.log.search(null, null, null, null, 50)).hasSize(3);
        assertThat(this.log.search(null, AuditLogDocument.SIGN_IN_FAILED, null, null, 50))
            .singleElement()
            .satisfies((line) -> {
                assertThat(line.actorId()).isEqualTo("owner-1");
                assertThat(line.ip()).isEqualTo("10.0.0.2");
                assertThat(line.status()).isEqualTo(401);
            });
        assertThat(this.log.search("admin-1", null, null, null, 50)).hasSize(2);
        assertThat(this.log.search(null, null, "promotions", null, 50)).extracting(AuditLogDocument::resource)
            .containsExactly("/api/v1/promotions/p1");

        List<AuditLogDocument> page = this.log.search(null, null, null, null, 2);
        assertThat(page).hasSize(2);
        assertThat(this.log.search(null, null, null, page.getLast().id(), 2)).hasSize(1);
    }

    @Test
    @DisplayName("only operator staff with identity:audit:read read the log")
    void readAccess() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("owner-1", "p", "store:portal"));
        assertThatThrownBy(() -> this.log.search(null, null, null, null, 10)).isInstanceOfSatisfying(ModuleException.class,
                (ex) -> assertThat(ex.code()).isEqualTo("AUDIT_ACCESS_DENIED"));
    }

    private static AuditEntry write(String resource, String after, int secondsLater) {
        return new AuditEntry(Ids.newId(), Instant.parse("2026-11-06T12:00:00Z").plusSeconds(secondsLater), "admin-1", null,
                List.of("ADMIN"), "stores", "PUT " + resource, resource, 200, after, after, "10.0.0.1", null);
    }
}
