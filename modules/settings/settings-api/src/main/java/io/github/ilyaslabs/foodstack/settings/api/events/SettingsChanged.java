package io.github.ilyaslabs.foodstack.settings.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A settings section changed. Topic {@code foodstack.settings.events}, type
 * {@code foodstack.settings.SettingsChanged.v1}; consumers re-read the section through
 * {@code SettingsApi} (for example search re-indexes when languages change).
 *
 * @param section {@code branding} or {@code locale}
 * @param version the section's new version
 * @param changedBy user who made the change
 */
public record SettingsChanged(String section, long version, String changedBy) implements ModuleEvent {

    public static final String BRANDING = "branding";

    public static final String LOCALE = "locale";

    @Override
    public String subject() {
        return this.section;
    }
}
