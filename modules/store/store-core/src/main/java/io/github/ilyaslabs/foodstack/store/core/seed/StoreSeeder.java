package io.github.ilyaslabs.foodstack.store.core.seed;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.store.api.StoreStatus;
import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import io.github.ilyaslabs.foodstack.store.api.events.BrandChanged;
import io.github.ilyaslabs.foodstack.store.api.events.StoreChanged;
import io.github.ilyaslabs.foodstack.store.core.application.Address;
import io.github.ilyaslabs.foodstack.store.core.application.Commission;
import io.github.ilyaslabs.foodstack.store.core.application.MenuDetails;
import io.github.ilyaslabs.foodstack.store.core.application.MenuItemInput;
import io.github.ilyaslabs.foodstack.store.core.application.MenuService;
import io.github.ilyaslabs.foodstack.store.core.application.ModifierGroup;
import io.github.ilyaslabs.foodstack.store.core.application.ModifierOption;
import io.github.ilyaslabs.foodstack.store.core.application.NewOwner;
import io.github.ilyaslabs.foodstack.store.core.application.StoreService;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreDocument;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Component;

/**
 * Two live demo restaurants in Lahore with menus in English, Arabic and Urdu, for the
 * {@code test-seed} profile (TDD 16): Spice Route uses the operator's riders, Napoli Slice
 * delivers itself. Writes only the store module's own data, once (marker in
 * {@code store_seed}); runs after the settings seeder has enabled the languages.
 */
@Component
@Profile("test-seed")
class StoreSeeder {

    private static final Log logger = LogFactory.getLog(StoreSeeder.class);

    private static final String SEED = "seed";

    /** Password of the demo restaurant owners, the same as the identity demo users (README). */
    static final String OWNER_PASSWORD = "foodstack-demo";

    private final BrandRepository brands;

    private final StoreRepository stores;

    private final MenuService menus;

    private final StoreService storeService;

    private final OutboxWriter outbox;

    private final ModuleMongo mongo;

    private final ServiceCalls serviceCalls;

    StoreSeeder(BrandRepository brands, StoreRepository stores, MenuService menus, StoreService storeService,
            OutboxWriter storeOutbox, ModuleMongo storeMongo, ServiceCalls serviceCalls) {
        this.serviceCalls = serviceCalls;
        this.brands = brands;
        this.stores = stores;
        this.menus = menus;
        this.storeService = storeService;
        this.outbox = storeOutbox;
        this.mongo = storeMongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    void seed() {
        try {
            this.mongo.template().insert(new Document("_id", "v1").append("seededAt", Instant.now()), "store_seed");
        }
        catch (DuplicateKeyException ex) {
            return;
        }
        // Seeding is the platform's own work: calls to identity and media run with a service token
        this.serviceCalls.run(() -> {
            spiceRoute();
            napoli();
            // Owners go through the identity module like the Owners tab does, so they sign in to the back office.
            this.storeService.addOwner(DemoStores.SPICE_ROUTE_GULBERG,
                    new NewOwner("spiceroute@demo.foodstack.local", "Spice Route Owner", OWNER_PASSWORD), SEED);
            this.storeService.addOwner(DemoStores.NAPOLI_DHA,
                    new NewOwner("napoli@demo.foodstack.local", "Napoli Slice Owner", OWNER_PASSWORD), SEED);
        });
        logger.info("Seeded demo restaurants Spice Route (Gulberg) and Napoli Slice (DHA), owners "
                + "spiceroute@demo.foodstack.local and napoli@demo.foodstack.local (password '" + OWNER_PASSWORD + "')");
    }

    private void spiceRoute() {
        restaurant(DemoStores.SPICE_ROUTE_BRAND, DemoStores.SPICE_ROUTE_GULBERG, "Spice Route", "Gulberg",
                Map.of("en", "Charcoal grills, karahi and fresh naan from the heart of Lahore.", "ar",
                        "مشويات على الفحم وكراهي وخبز نان طازج من قلب لاهور.", "ur",
                        "لاہور کے دل سے کوئلے کے تکے، کڑاہی اور تازہ نان۔"),
                List.of("pakistani", "bbq", "desi"), new Address("42 Main Boulevard", "Gulberg III", "Lahore", "54660"),
                31.5120, 74.3440, 25, DeliveryMode.OPERATOR);
        String grills = section(DemoStores.SPICE_ROUTE_BRAND, "Grills", "مشويات", "تکے اور کباب");
        String curries = section(DemoStores.SPICE_ROUTE_BRAND, "Karahi and curries", "كراهي وكاري", "کڑاہی اور سالن");
        String breads = section(DemoStores.SPICE_ROUTE_BRAND, "Breads", "خبز", "روٹی اور نان");
        item(DemoStores.SPICE_ROUTE_BRAND, grills, "Chicken tikka", "تكا الدجاج", "چکن تکہ",
                "Quarter chicken marinated overnight in yoghurt and spices", "ربع دجاجة متبلة باللبن والبهارات",
                "دہی اور مصالحوں میں رات بھر میرینیٹ کیا ہوا چوتھائی مرغ", 65_000, List.of("halal", "spicy"), 0);
        item(DemoStores.SPICE_ROUTE_BRAND, grills, "Seekh kebab (4 pcs)", "سيخ كباب (٤ قطع)", "سیخ کباب (4 عدد)",
                "Minced beef with green chilli and coriander", "لحم بقري مفروم مع الفلفل الأخضر والكزبرة",
                "ہری مرچ اور دھنیے کے ساتھ قیمہ", 72_000, List.of("halal"), 1);
        item(DemoStores.SPICE_ROUTE_BRAND, curries, "Chicken karahi", "كراهي الدجاج", "چکن کڑاہی",
                "Cooked in a wok with tomatoes, ginger and green chillies", "مطبوخ مع الطماطم والزنجبيل والفلفل الأخضر",
                "ٹماٹر، ادرک اور ہری مرچ کے ساتھ کڑاہی میں پکی ہوئی", 145_000, List.of("halal", "spicy"), 0, sizes());
        item(DemoStores.SPICE_ROUTE_BRAND, curries, "Daal makhni", "دال مخني", "دال مکھنی",
                "Slow-cooked black lentils with butter and cream", "عدس أسود مطبوخ ببطء مع الزبدة والقشدة",
                "مکھن اور کریم کے ساتھ دھیمی آنچ پر پکی ہوئی ماش کی دال", 55_000, List.of("vegetarian", "halal"), 1);
        item(DemoStores.SPICE_ROUTE_BRAND, breads, "Garlic naan", "نان بالثوم", "گارلک نان",
                "Fresh from the tandoor", "طازج من التنور", "تندور سے تازہ", 12_000, List.of("vegetarian"), 0);
    }

    private void napoli() {
        restaurant(DemoStores.NAPOLI_BRAND, DemoStores.NAPOLI_DHA, "Napoli Slice", "DHA Phase 5",
                Map.of("en", "Wood-fired Neapolitan pizza with a 48-hour dough.", "ar",
                        "بيتزا نابولية في فرن الحطب بعجينة مخمرة ٤٨ ساعة.", "ur",
                        "48 گھنٹے خمیر شدہ آٹے کے ساتھ لکڑی کے تندور کا نیپولیٹن پیزا۔"),
                List.of("pizza", "italian"), new Address("12 Commercial Area, Phase 5", "DHA", "Lahore", "54792"), 31.4630,
                74.4080, 20, DeliveryMode.SELF);
        String pizzas = section(DemoStores.NAPOLI_BRAND, "Pizzas", "بيتزا", "پیزا");
        item(DemoStores.NAPOLI_BRAND, pizzas, "Margherita", "مارغريتا", "مارگریٹا",
                "Tomato, fior di latte, basil", "طماطم وجبن فيور دي لاتي وريحان", "ٹماٹر، فیور دی لاتے، تلسی", 180_000,
                List.of("vegetarian"), 0);
        item(DemoStores.NAPOLI_BRAND, pizzas, "Spicy chicken tikka pizza", "بيتزا تكا الدجاج الحارة", "اسپائسی چکن تکہ پیزا",
                "Our Lahori twist with tikka, onions and green chilli", "لمستنا اللاهورية مع التكا والبصل والفلفل",
                "تکہ، پیاز اور ہری مرچ کے ساتھ ہمارا لاہوری انداز", 220_000, List.of("halal", "spicy"), 1);
    }

    private void restaurant(String brandId, String storeId, String brandName, String branchName,
            Map<String, String> description, List<String> cuisines, Address address, double lat, double lng, int prep,
            DeliveryMode deliveryMode) {
        Instant now = Instant.now();
        BrandDocument brand = new BrandDocument();
        brand.setId(brandId);
        brand.setName(brandName);
        brand.setDescription(new java.util.LinkedHashMap<>(description));
        brand.setCuisines(new ArrayList<>(cuisines));
        brand.setCommission(new Commission(1500, 0));
        brand.setBranchCount(1);
        brand.setCreatedAt(now);
        brand.setCreatedBy(SEED);
        brand.setUpdatedAt(now);
        brand.setUpdatedBy(SEED);
        StoreDocument store = new StoreDocument();
        store.setId(storeId);
        store.setBrandId(brandId);
        store.setBrandName(brandName);
        store.setBranchName(branchName);
        store.setLegalName(brandName + " (Pvt) Ltd");
        store.setAddress(address);
        store.setLocation(new GeoJsonPoint(lng, lat));
        Map<DayOfWeek, List<TimeRange>> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, List.of(new TimeRange("00:00", "23:59")));
        }
        store.setOpeningHours(hours);
        store.setPrepMinutes(prep);
        store.setDeliveryMode(deliveryMode);
        store.setPickupEnabled(true);
        store.setStatus(StoreStatus.LIVE);
        store.setCreatedAt(now);
        store.setCreatedBy(SEED);
        store.setUpdatedAt(now);
        store.setUpdatedBy(SEED);
        this.mongo.transactionTemplate().executeWithoutResult((status) -> {
            BrandDocument savedBrand = this.brands.insert(brand);
            StoreDocument saved = this.stores.insert(store);
            this.outbox.append(new BrandChanged(brandId, savedBrand.getVersion(), SEED));
            this.outbox.append(new StoreChanged(storeId, brandId, saved.getVersion(), SEED));
        });
    }

    private String section(String brandId, String en, String ar, String ur) {
        MenuDetails.Section section = this.menus.createSection(brandId, Map.of("en", en, "ar", ar, "ur", ur), SEED);
        return section.id();
    }

    private void item(String brandId, String sectionId, String en, String ar, String ur, String descEn, String descAr,
            String descUr, long price, List<String> tags, int order, ModifierGroup... groups) {
        this.menus.createItem(brandId, new MenuItemInput(sectionId, Map.of("en", en, "ar", ar, "ur", ur),
                Map.of("en", descEn, "ar", descAr, "ur", descUr), price, null, tags, List.of(groups), null, order), SEED);
    }

    private static ModifierGroup sizes() {
        return new ModifierGroup(null, Map.of("en", "Size", "ar", "الحجم", "ur", "سائز"), 1, 1,
                List.of(new ModifierOption(null, Map.of("en", "Half", "ar", "نصف", "ur", "ہاف"), 0),
                        new ModifierOption(null, Map.of("en", "Full", "ar", "كامل", "ur", "فل"), 125_000)));
    }
}
