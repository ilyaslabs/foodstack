package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.settings.core.persistence.BrandingDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.BrandingRepository;
import io.github.ilyaslabs.foodstack.settings.core.persistence.LocaleDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.LocaleRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * Branding and locale of the deployment. Reads are cached; every change is versioned, checked
 * against the version the editor saw, and published as {@link SettingsChanged}.
 */
public class SettingsService {

    static final MediaOwner LOGO_OWNER = new MediaOwner("settings", "branding");

    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    private static final Pattern FONT = Pattern.compile("[A-Za-z0-9 \\-]{1,60}");

    private final BrandingRepository brandingRepository;

    private final LocaleRepository localeRepository;

    private final MediaApi mediaApi;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final SettingsCache cache;

    private final Clock clock;

    public SettingsService(BrandingRepository brandingRepository, LocaleRepository localeRepository, MediaApi mediaApi,
            OutboxWriter outbox, TransactionTemplate transaction, SettingsCache cache, Clock clock) {
        this.brandingRepository = brandingRepository;
        this.localeRepository = localeRepository;
        this.mediaApi = mediaApi;
        this.outbox = outbox;
        this.transaction = transaction;
        this.cache = cache;
        this.clock = clock;
    }

    public BrandingDetails getBranding() {
        return this.cache.get(SettingsChanged.BRANDING, BrandingDetails.class, () -> branding(loadBranding()));
    }

    public LocaleDetails getLocale() {
        return this.cache.get(SettingsChanged.LOCALE, LocaleDetails.class, () -> locale(loadLocale()));
    }

    /**
     * Replaces the branding.
     * @param expectedVersion the version the editor started from; a different current version fails with 409
     */
    public BrandingDetails updateBranding(UpdateBranding update, long expectedVersion, String userId) {
        validate(update);
        BrandingDocument branding = loadBranding();
        requireVersion("branding", branding.getVersion(), expectedVersion);
        String oldLogo = branding.getLogoMediaId();
        String newLogo = blankToNull(update.logoMediaId());
        if (newLogo != null && !newLogo.equals(oldLogo)) {
            this.mediaApi.claim(newLogo, MediaPurpose.BRANDING, LOGO_OWNER);
        }
        branding.setName(update.name().trim());
        branding.setTagline(blankToNull(update.tagline()));
        branding.setPrimaryColor(update.primaryColor().toUpperCase(Locale.ROOT));
        branding.setSecondaryColor(update.secondaryColor().toUpperCase(Locale.ROOT));
        branding.setFontFamily(update.fontFamily().trim());
        branding.setLogoMediaId(newLogo);
        branding.setUpdatedAt(this.clock.instant());
        branding.setUpdatedBy(userId);
        BrandingDocument saved = this.transaction.execute((status) -> {
            BrandingDocument result = this.brandingRepository.save(branding);
            this.outbox.append(new SettingsChanged(SettingsChanged.BRANDING, result.getVersion(), userId));
            return result;
        });
        this.cache.evict(SettingsChanged.BRANDING);
        if (oldLogo != null && !oldLogo.equals(newLogo)) {
            this.mediaApi.release(oldLogo, LOGO_OWNER);
        }
        return branding(saved);
    }

    public LocaleDetails updateLocale(UpdateLocale update, long expectedVersion, String userId) {
        List<String> languages = validate(update);
        LocaleDocument locale = loadLocale();
        requireVersion("locale", locale.getVersion(), expectedVersion);
        locale.setDefaultLanguage(update.defaultLanguage());
        locale.setLanguages(languages);
        locale.setCurrency(update.currency().toUpperCase(Locale.ROOT));
        locale.setTimezone(update.timezone());
        locale.setUpdatedAt(this.clock.instant());
        locale.setUpdatedBy(userId);
        LocaleDocument saved = this.transaction.execute((status) -> {
            LocaleDocument result = this.localeRepository.save(locale);
            this.outbox.append(new SettingsChanged(SettingsChanged.LOCALE, result.getVersion(), userId));
            return result;
        });
        this.cache.evict(SettingsChanged.LOCALE);
        return locale(saved);
    }

    private BrandingDocument loadBranding() {
        return this.brandingRepository.findById(BrandingDocument.ID)
            .orElseThrow(() -> new ModuleException.Internal("Branding settings are missing; run the migrations",
                    "SETTINGS_MISSING"));
    }

    private LocaleDocument loadLocale() {
        return this.localeRepository.findById(LocaleDocument.ID)
            .orElseThrow(() -> new ModuleException.Internal("Locale settings are missing; run the migrations",
                    "SETTINGS_MISSING"));
    }

    private BrandingDetails branding(BrandingDocument document) {
        Map<String, String> logoUrls = new LinkedHashMap<>();
        if (document.getLogoMediaId() != null) {
            MediaAssetView logo = this.mediaApi.getAsset(document.getLogoMediaId());
            logo.variants().forEach((name, variant) -> logoUrls.put(name, variant.url()));
        }
        return new BrandingDetails(document.getName(), document.getTagline(), document.getPrimaryColor(),
                document.getSecondaryColor(), document.getFontFamily(), document.getLogoMediaId(), logoUrls,
                version(document.getVersion()), document.getUpdatedAt());
    }

    private static LocaleDetails locale(LocaleDocument document) {
        return new LocaleDetails(document.getDefaultLanguage(), List.copyOf(document.getLanguages()),
                document.getCurrency(), document.getTimezone(), version(document.getVersion()), document.getUpdatedAt());
    }

    private static void requireVersion(String section, Long current, long expected) {
        if (version(current) != expected) {
            throw new ModuleException.Conflict("The " + section + " settings were changed by someone else; reload and retry",
                    "SETTINGS_VERSION_CONFLICT", Map.of("currentVersion", version(current)));
        }
    }

    private static void validate(UpdateBranding update) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (update.name() == null || update.name().isBlank() || update.name().trim().length() > 60) {
            errors.put("name", "must be 1 to 60 characters");
        }
        if (update.tagline() != null && update.tagline().length() > 120) {
            errors.put("tagline", "must be at most 120 characters");
        }
        if (update.primaryColor() == null || !COLOR.matcher(update.primaryColor()).matches()) {
            errors.put("primaryColor", "must be a #RRGGBB colour");
        }
        if (update.secondaryColor() == null || !COLOR.matcher(update.secondaryColor()).matches()) {
            errors.put("secondaryColor", "must be a #RRGGBB colour");
        }
        if (update.fontFamily() == null || !FONT.matcher(update.fontFamily().trim()).matches()) {
            errors.put("fontFamily", "may contain letters, digits, spaces and hyphens");
        }
        failIfAny(errors);
    }

    private static List<String> validate(UpdateLocale update) {
        Map<String, String> errors = new LinkedHashMap<>();
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        if (update.languages() == null || update.languages().isEmpty()) {
            errors.put("languages", "must list at least one language");
        }
        else {
            for (String tag : update.languages()) {
                String canonical = (tag != null) ? Locale.forLanguageTag(tag).toLanguageTag() : "und";
                if ("und".equals(canonical) || !canonical.equalsIgnoreCase(tag)) {
                    errors.put("languages", "must be BCP 47 language tags such as en, ar, ur-PK");
                }
                languages.add(canonical);
            }
        }
        String defaultLanguage = (update.defaultLanguage() != null)
                ? Locale.forLanguageTag(update.defaultLanguage()).toLanguageTag() : null;
        if (defaultLanguage == null || !languages.contains(defaultLanguage)) {
            errors.put("defaultLanguage", "must be one of the languages");
        }
        try {
            Currency.getInstance(Objects.requireNonNullElse(update.currency(), "").toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            errors.put("currency", "must be an ISO 4217 code");
        }
        try {
            ZoneId.of(Objects.requireNonNullElse(update.timezone(), ""));
        }
        catch (DateTimeException ex) {
            errors.put("timezone", "must be an IANA time zone such as Asia/Karachi");
        }
        failIfAny(errors);
        languages.remove(defaultLanguage);
        List<String> ordered = new java.util.ArrayList<>();
        ordered.add(defaultLanguage);
        ordered.addAll(languages);
        return List.copyOf(ordered);
    }

    private static void failIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The settings are invalid", "SETTINGS_INVALID",
                    Map.of("errors", errors));
        }
    }

    private static long version(Long version) {
        return (version != null) ? version : 0L;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
