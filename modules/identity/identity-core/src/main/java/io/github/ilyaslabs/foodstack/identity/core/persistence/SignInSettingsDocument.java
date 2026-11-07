package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The operator's sign-in options in {@code identity_settings} (one document, {@code _id}
 * {@code sign-in}; PRD ID-2, SG-6): which social providers are on, and this deployment's client
 * ids with each. Client ids are public (the apps show them to the provider). Unsharded: one tiny
 * document.
 *
 * @param id always {@code sign-in}
 * @param googleClientIds Google OAuth client ids (web, Android, iOS); empty = Google off
 * @param appleClientIds Apple Services ID and app bundle ids; empty = Apple off
 * @param updatedBy who changed it last
 * @param updatedAt when
 */
@Document("identity_settings")
public record SignInSettingsDocument(@Id String id, List<String> googleClientIds, List<String> appleClientIds,
        String updatedBy, Instant updatedAt) {

    public static final String ID = "sign-in";

    public SignInSettingsDocument {
        googleClientIds = (googleClientIds != null) ? List.copyOf(googleClientIds) : List.of();
        appleClientIds = (appleClientIds != null) ? List.copyOf(appleClientIds) : List.of();
    }
}
