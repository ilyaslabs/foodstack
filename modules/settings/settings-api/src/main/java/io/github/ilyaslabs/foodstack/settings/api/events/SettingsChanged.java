package io.github.ilyaslabs.foodstack.settings.api.events;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A settings section changed. Topic {@code foodstack.settings.events}, type
 * {@code foodstack.settings.SettingsChanged.v1}; consumers re-read the section through
 * {@code SettingsApi} (for example search re-indexes when languages change).
 *
 * @param section {@code branding}, {@code locale}, {@code ordering} or {@code delivery}
 * @param version the section's new version
 * @param changedBy user who made the change
 */
@EVL.Event(id = "settings.settings-changed", name = "Settings Changed", channel = "<namespace>.settings.events",
        delivery = EVL.Delivery.AT_LEAST_ONCE, ordering = "Per section (Kafka key = section)",
        schema = "foodstack.settings.SettingsChanged.v1", broker = "platform.kafka")
public record SettingsChanged(String section, long version, String changedBy) implements ModuleEvent {

    public static final String BRANDING = "branding";

    public static final String LOCALE = "locale";

    public static final String ORDERING = "ordering";

    public static final String DELIVERY = "delivery";

    @Override
    public String subject() {
        return this.section;
    }
}
