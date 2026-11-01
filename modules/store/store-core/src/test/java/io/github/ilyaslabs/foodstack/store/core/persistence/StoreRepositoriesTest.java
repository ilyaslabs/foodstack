package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.core.StoreConfiguration;
import io.github.ilyaslabs.foodstack.store.core.application.Address;
import io.github.ilyaslabs.foodstack.store.core.application.ModifierGroup;
import io.github.ilyaslabs.foodstack.store.core.application.ModifierOption;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = StoreRepositoriesTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class StoreRepositoriesTest {

    /** The persistence part of StoreConfiguration, without Kafka and Redis. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMongoRepositories(basePackageClasses = StoreRepository.class, mongoTemplateRef = "storeMongoTemplate")
    static class App {

        @Bean
        ModuleMongo storeMongo(ModuleMongoFactory factory) {
            return factory.create("store");
        }

        @Bean
        MongoTemplate storeMongoTemplate(ModuleMongo storeMongo) {
            return storeMongo.template();
        }

        @Bean
        ModuleMigrations storeMigrations(ModuleMongo storeMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(storeMongo, factory.connectionString("store"), StoreConfiguration.shardKeys(),
                    properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    BrandRepository brands;

    @Autowired
    StoreRepository stores;

    @Autowired
    StoreSlugRepository slugs;

    @Autowired
    MenuSectionRepository sections;

    @Autowired
    MenuItemRepository items;

    @Autowired
    ItemOverrideRepository overrides;

    @Autowired
    MongoTemplate storeMongoTemplate;

    @Test
    @DisplayName("keeps brands and branches sharded on hashed _id and pages branches by id")
    void brandsAndBranches() {
        // given
        BrandDocument brand = new BrandDocument();
        brand.setId(Ids.newId());
        brand.setName("Spice Route");
        brand.setBranchCount(2);
        brand.setCreatedAt(Instant.now());
        this.brands.insert(brand);
        StoreDocument first = this.stores.insert(store(brand.getId(), "Gulberg"));
        StoreDocument second = this.stores.insert(store(brand.getId(), "DHA"));

        // when
        List<StoreDocument> ofBrand = this.stores.findByBrandId(brand.getId());
        List<StoreDocument> page = this.stores.findByIdGreaterThanOrderByIdAsc(first.getId(), Limit.of(10));
        List<StoreDocument> byIds = this.stores.findByIdInOrderByIdDesc(Set.of(first.getId(), second.getId()));

        // then
        assertThat(ofBrand).extracting(StoreDocument::getBranchName).containsExactlyInAnyOrder("Gulberg", "DHA");
        assertThat(page).extracting(StoreDocument::getId).contains(second.getId()).doesNotContain(first.getId());
        assertThat(byIds).extracting(StoreDocument::getId).containsExactly(second.getId(), first.getId());
        StoreDocument loaded = this.stores.findById(first.getId()).orElseThrow();
        assertThat(loaded.getLocation().getX()).isEqualTo(74.35);
        assertThat(loaded.getAddress().city()).isEqualTo("Lahore");
        assertThat(loaded.getVersion()).isNotNull();
        assertThat(shardKey("store_brands")).isEqualTo(new Document("_id", "hashed"));
        assertThat(shardKey("store_stores")).isEqualTo(new Document("_id", "hashed"));
    }

    @Test
    @DisplayName("keeps one owner per web address and finds branches that have none yet")
    void slugs() {
        // given
        String slug = "spice-route-" + Ids.newId().substring(0, 8);
        StoreDocument withSlug = store(Ids.newId(), "Gulberg");
        withSlug.setSlug(slug);
        StoreDocument withoutSlug = this.stores.insert(store(Ids.newId(), "DHA"));
        this.stores.insert(withSlug);
        this.slugs.insert(new StoreSlugDocument(slug, withSlug.getId(), Instant.now()));

        // when
        List<StoreDocument> missing = this.stores.findBySlugIsNull(Limit.of(500));

        // then
        assertThat(missing).extracting(StoreDocument::getId).contains(withoutSlug.getId()).doesNotContain(withSlug.getId());
        assertThat(this.slugs.findById(slug)).get().extracting(StoreSlugDocument::getStoreId).isEqualTo(withSlug.getId());
        assertThatThrownBy(() -> this.slugs.insert(new StoreSlugDocument(slug, Ids.newId(), Instant.now())))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(shardKey("store_slugs")).isEqualTo(new Document("_id", "hashed"));
    }

    @Test
    @DisplayName("keeps a brand's menu sharded on hashed brandId, hides deleted items and counts per section")
    void menu() {
        // given
        String brandId = Ids.newId();
        MenuSectionDocument mains = section(brandId, 1);
        MenuSectionDocument starters = section(brandId, 0);
        this.sections.insert(List.of(mains, starters));
        MenuItemDocument kept = item(brandId, starters.getId(), 0);
        MenuItemDocument deleted = item(brandId, starters.getId(), 1);
        deleted.setDeletedAt(Instant.now());
        this.items.insert(List.of(kept, deleted, item(brandId, mains.getId(), 0)));

        // when
        List<MenuSectionDocument> ordered = this.sections.findByBrandIdOrderBySortOrderAsc(brandId);
        List<MenuItemDocument> live = this.items.findByBrandIdAndDeletedAtIsNullOrderBySortOrderAsc(brandId);

        // then
        assertThat(ordered).extracting(MenuSectionDocument::getId).containsExactly(starters.getId(), mains.getId());
        assertThat(live).hasSize(2).noneMatch((i) -> i.getId().equals(deleted.getId()));
        assertThat(this.items.countByBrandIdAndSectionIdAndDeletedAtIsNull(brandId, starters.getId())).isEqualTo(1);
        assertThat(this.items.countByBrandIdAndDeletedAtIsNull(brandId)).isEqualTo(2);
        assertThat(this.sections.countByBrandId(brandId)).isEqualTo(2);
        MenuItemDocument loaded = this.items.findById(kept.getId()).orElseThrow();
        assertThat(loaded.getName()).containsEntry("ar", "برياني");
        assertThat(loaded.getModifierGroups().getFirst().options()).hasSize(2);
        assertThat(shardKey("store_menu_sections")).isEqualTo(new Document("brandId", "hashed"));
        assertThat(shardKey("store_menu_items")).isEqualTo(new Document("brandId", "hashed"));
    }

    @Test
    @DisplayName("keeps a branch's sold-out marks together, sharded on hashed storeId")
    void overrides() {
        // given
        String storeId = Ids.newId();
        ItemOverrideDocument override = new ItemOverrideDocument();
        override.setId(storeId + ":item-1");
        override.setStoreId(storeId);
        override.setItemId("item-1");
        override.setSoldOut(true);
        override.setUpdatedAt(Instant.now());
        this.overrides.insert(override);

        // when
        List<ItemOverrideDocument> found = this.overrides.findByStoreId(storeId);

        // then
        assertThat(found).singleElement().satisfies((o) -> assertThat(o.isSoldOut()).isTrue());
        assertThat(shardKey("store_item_overrides")).isEqualTo(new Document("storeId", "hashed"));
    }

    private Document shardKey(String collection) {
        String db = this.storeMongoTemplate.getDb().getName();
        return this.storeMongoTemplate.getMongoDatabaseFactory()
            .getMongoDatabase("config")
            .getCollection("collections")
            .find(new Document("_id", db + "." + collection))
            .first()
            .get("key", Document.class);
    }

    private static StoreDocument store(String brandId, String branchName) {
        StoreDocument store = new StoreDocument();
        store.setId(Ids.newId());
        store.setBrandId(brandId);
        store.setBrandName("Spice Route");
        store.setBranchName(branchName);
        store.setLegalName("Spice Route (Pvt) Ltd");
        store.setAddress(new Address("1 Main Boulevard", branchName, "Lahore", null));
        store.setLocation(new GeoJsonPoint(74.35, 31.52));
        store.setPrepMinutes(20);
        store.setDeliveryMode(DeliveryMode.SELF);
        store.setStatus(StoreStatus.DRAFT);
        store.setCreatedAt(Instant.now());
        store.setUpdatedAt(Instant.now());
        return store;
    }

    private static MenuSectionDocument section(String brandId, int sortOrder) {
        MenuSectionDocument section = new MenuSectionDocument();
        section.setId(Ids.newId());
        section.setBrandId(brandId);
        section.setName(Map.of("en", "Section " + sortOrder));
        section.setSortOrder(sortOrder);
        section.setCreatedAt(Instant.now());
        return section;
    }

    private static MenuItemDocument item(String brandId, String sectionId, int sortOrder) {
        MenuItemDocument item = new MenuItemDocument();
        item.setId(Ids.newId());
        item.setBrandId(brandId);
        item.setSectionId(sectionId);
        item.setName(new java.util.LinkedHashMap<>(Map.of("en", "Biryani", "ar", "برياني")));
        item.setPriceMinor(85000);
        item.setSortOrder(sortOrder);
        item.setModifierGroups(List.of(new ModifierGroup("size", Map.of("en", "Size"), 1, 1,
                List.of(new ModifierOption("half", Map.of("en", "Half"), 0),
                        new ModifierOption("full", Map.of("en", "Full"), 40000)))));
        item.setCreatedAt(Instant.now());
        return item;
    }
}
