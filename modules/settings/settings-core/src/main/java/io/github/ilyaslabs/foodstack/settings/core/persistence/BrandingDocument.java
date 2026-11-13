package io.github.ilyaslabs.foodstack.settings.core.persistence;

import java.time.Instant;

import io.github.ilyaslabs.evl.EVL;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** The {@code branding} document of {@code settings_settings} (unsharded: one document per section). */
@EVL.DataModel(id = "settings.branding", name = "Branding", module = "settings", owner = "settings",
        storage = "MongoDB foodstack_settings.settings_settings, _id branding (unsharded)")
@Document("settings_settings")
public class BrandingDocument {

    public static final String ID = "branding";

    @Id
    private String id = ID;

    private String name;

    private String tagline;

    private String primaryColor;

    private String secondaryColor;

    private String fontFamily;

    @EVL.Field(meaning = "Media id of the logo, claimed in the media module for settings/branding")
    @EVL.Relationship(target = "media.asset", cardinality = EVL.Cardinality.ONE_TO_ONE,
            ownership = EVL.Ownership.REFERENCE)
    private String logoMediaId;

    private Instant updatedAt;

    private String updatedBy;

    @EVL.Field(meaning = "Optimistic lock; editors send it back as If-Match")
    @Version
    private Long version;

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTagline() {
        return this.tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getPrimaryColor() {
        return this.primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public String getSecondaryColor() {
        return this.secondaryColor;
    }

    public void setSecondaryColor(String secondaryColor) {
        this.secondaryColor = secondaryColor;
    }

    public String getFontFamily() {
        return this.fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public String getLogoMediaId() {
        return this.logoMediaId;
    }

    public void setLogoMediaId(String logoMediaId) {
        this.logoMediaId = logoMediaId;
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
