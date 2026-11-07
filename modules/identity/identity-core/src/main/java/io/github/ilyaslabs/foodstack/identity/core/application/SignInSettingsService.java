package io.github.ilyaslabs.foodstack.identity.core.application;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.identity.core.persistence.SignInSettingsDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Social sign-in switches (PRD ID-2, SG-6): an administrator turns Google or Apple on by entering
 * the deployment's client ids. Read at each social sign-in and by the apps to show the buttons.
 */
public class SignInSettingsService {

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{3,200}");

    private final MongoTemplate mongo;

    private final Clock clock;

    public SignInSettingsService(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    public SignInProviders get() {
        SignInSettingsDocument settings = read();
        return new SignInProviders(settings.googleClientIds(), settings.appleClientIds());
    }

    private SignInSettingsDocument read() {
        SignInSettingsDocument found = this.mongo.findById(SignInSettingsDocument.ID, SignInSettingsDocument.class);
        return (found != null) ? found : new SignInSettingsDocument(SignInSettingsDocument.ID, List.of(), List.of(), null, null);
    }

    public List<String> clientIds(String provider) {
        SignInProviders settings = get();
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "google" -> settings.googleClientIds();
            case "apple" -> settings.appleClientIds();
            default -> List.of();
        };
    }

    public SignInProviders update(Collection<String> googleClientIds, Collection<String> appleClientIds, String by) {
        List<String> google = clean("googleClientIds", googleClientIds);
        List<String> apple = clean("appleClientIds", appleClientIds);
        this.mongo.save(new SignInSettingsDocument(SignInSettingsDocument.ID, google, apple, by, this.clock.instant()));
        return new SignInProviders(google, apple);
    }

    private static List<String> clean(String field, Collection<String> ids) {
        if (ids == null) {
            return List.of();
        }
        List<String> cleaned = ids.stream().map(String::trim).filter((id) -> !id.isEmpty()).distinct().toList();
        if (cleaned.size() > 10 || cleaned.stream().anyMatch((id) -> !CLIENT_ID.matcher(id).matches())) {
            throw new ModuleException.BadRequest("Client ids are letters, digits, dots and dashes (at most 10)",
                    "CLIENT_ID_INVALID", Map.of("errors", Map.of(field, "has an invalid client id")));
        }
        return cleaned;
    }
}
