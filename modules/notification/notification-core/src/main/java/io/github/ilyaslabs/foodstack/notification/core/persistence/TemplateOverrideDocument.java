package io.github.ilyaslabs.foodstack.notification.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The operator's own wording of one message in one language (PRD NO-2). A blank field keeps the
 * built-in text; deleting the document goes back to the built-in text entirely.
 *
 * @param id {@code <template>:<language>}
 * @param template template id, such as {@code order-confirmed}
 * @param language BCP 47 tag
 * @param title in-app headline and email heading
 * @param body text
 * @param subject email subject
 * @param version increases with every save, for If-Match
 * @param updatedAt last save
 * @param updatedBy who saved it
 */
@Document("notification_templates")
public record TemplateOverrideDocument(@Id String id, String template, String language, String title, String body,
        String subject, long version, Instant updatedAt, String updatedBy) {

    public static String id(String template, String language) {
        return template + ":" + language;
    }
}
