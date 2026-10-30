package io.github.ilyaslabs.foodstack.settings.core.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** The {@code locale} document of {@code settings_settings}. */
@Document("settings_settings")
public class LocaleDocument {

    public static final String ID = "locale";

    @Id
    private String id = ID;

    private String defaultLanguage;

    private List<String> languages = new ArrayList<>();

    private String currency;

    private String timezone;

    private Instant updatedAt;

    private String updatedBy;

    @Version
    private Long version;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDefaultLanguage() {
        return this.defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public List<String> getLanguages() {
        return this.languages;
    }

    public void setLanguages(List<String> languages) {
        this.languages = languages;
    }

    public String getCurrency() {
        return this.currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getTimezone() {
        return this.timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Instant getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return this.updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getVersion() {
        return this.version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
