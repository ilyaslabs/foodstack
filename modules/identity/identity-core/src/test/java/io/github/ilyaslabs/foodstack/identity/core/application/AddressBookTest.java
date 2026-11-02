package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.core.IdentityPersistenceTestApplication;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Saved addresses against a real two-shard MongoDB (lowest layer, TDD 15.3). */
@SpringBootTest(classes = IdentityPersistenceTestApplication.class, properties = "spring.data.mongodb.repositories.type=none")
class AddressBookTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo identityMongo;

    private AddressBook book;

    @BeforeEach
    void setUp() {
        this.identityMongo.template().remove(new Query(), "identity_addresses");
        this.book = new AddressBook(this.identityMongo.template(), this.identityMongo.transactionTemplate(), Clock.systemUTC());
    }

    private static AddressInput home() {
        return new AddressInput("home", null, 31.5204, 74.3587, " 12 Main Boulevard, Gulberg III ", "Blue gate", false);
    }

    @Test
    @DisplayName("the first address is preferred, a new preferred one takes over, and the list starts with it")
    void preferredAddress() {
        SavedAddress home = this.book.add("u1", home());
        assertThat(home.preferred()).isTrue();
        assertThat(home.label()).isEqualTo("HOME");
        assertThat(home.addressLine()).isEqualTo("12 Main Boulevard, Gulberg III");

        SavedAddress mum = this.book.add("u1",
                new AddressInput("OTHER", "Mum's place", 31.47, 74.40, "House 5, DHA Phase 5", null, true));
        SavedAddress work = this.book.add("u1", new AddressInput("WORK", "ignored", 31.5, 74.33, "Office 3, Arfa Tower", null, false));

        List<SavedAddress> list = this.book.list("u1");
        assertThat(list).extracting(SavedAddress::id).containsExactly(mum.id(), home.id(), work.id());
        assertThat(list).filteredOn(SavedAddress::preferred).hasSize(1);
        assertThat(work.name()).isNull();
        assertThat(this.book.list("someone-else")).isEmpty();
    }

    @Test
    @DisplayName("one Home and one Work per customer, a pin and an address line are required")
    void validation() {
        this.book.add("u1", home());
        assertThatThrownBy(() -> this.book.add("u1", home()))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("ADDRESS_LABEL_TAKEN"));
        assertThatThrownBy(() -> this.book.add("u1", new AddressInput("WORK", null, 0, 0, "x", null, false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("LOCATION_INVALID"));
        assertThatThrownBy(() -> this.book.add("u1", new AddressInput("WORK", null, 31, 74, "  ", null, false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("ADDRESS_LINE_REQUIRED"));
        assertThatThrownBy(() -> this.book.add("u1", new AddressInput("CASTLE", null, 31, 74, "x", null, false)))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("ADDRESS_LABEL_INVALID"));
        // the same label for another customer is fine
        assertThat(this.book.add("u2", home()).label()).isEqualTo("HOME");
    }

    @Test
    @DisplayName("an edit checks the version, and removing the preferred address passes the role on")
    void editAndRemove() {
        SavedAddress home = this.book.add("u1", home());
        SavedAddress work = this.book.add("u1", new AddressInput("WORK", null, 31.5, 74.33, "Arfa Tower", null, false));

        SavedAddress moved = this.book.update("u1", work.id(),
                new AddressInput("WORK", null, 31.51, 74.34, "Arfa Tower, floor 9", "Ask at reception", false), work.version());
        assertThat(moved.addressLine()).isEqualTo("Arfa Tower, floor 9");
        assertThat(moved.version()).isGreaterThan(work.version());
        assertThatThrownBy(() -> this.book.update("u1", work.id(),
                new AddressInput("WORK", null, 31.51, 74.34, "Old", null, false), work.version()))
            .isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("ADDRESS_CHANGED"));
        assertThatThrownBy(() -> this.book.update("u2", work.id(), home(), null))
            .isInstanceOf(ModuleException.NotFound.class);

        this.book.remove("u1", home.id());
        assertThat(this.book.list("u1")).singleElement().satisfies((only) -> {
            assertThat(only.id()).isEqualTo(work.id());
            assertThat(only.preferred()).isTrue();
        });
        assertThatThrownBy(() -> this.book.remove("u1", home.id())).isInstanceOf(ModuleException.NotFound.class);
    }

    @Test
    @DisplayName("a customer keeps at most 20 addresses")
    void limit() {
        for (int i = 0; i < AddressBook.MAX_ADDRESSES; i++) {
            this.book.add("u1", new AddressInput("OTHER", "Place " + i, 31 + i / 100.0, 74, "Street " + i, null, false));
        }
        assertThatThrownBy(() -> this.book.add("u1", home())).isInstanceOfSatisfying(ModuleException.class, (ex) -> assertThat(ex.code()).isEqualTo("ADDRESS_LIMIT"));
    }
}
