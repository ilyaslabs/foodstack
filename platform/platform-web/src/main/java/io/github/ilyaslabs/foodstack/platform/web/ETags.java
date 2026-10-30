package io.github.ilyaslabs.foodstack.platform.web;

import java.util.regex.Pattern;

import io.github.ilyaslabs.moduleswitch.ModuleException;

/**
 * Entity tags for versioned resources: {@code ETag}/{@code If-None-Match} for cached reads and
 * {@code If-Match} for back-office edits, so concurrent edits never overwrite each other (TDD 8.1).
 */
public final class ETags {

    private static final Pattern ENCODING_SUFFIX = Pattern.compile("-(gzip|zstd|br|deflate)\"$");

    private ETags() {
    }

    /** A strong tag for a resource version, such as {@code "branding-7"}. */
    public static String of(String resource, long version) {
        return "\"" + resource + "-" + version + "\"";
    }

    /**
     * The version an {@code If-Match} header names, or {@code null} when the header is absent.
     * Fails with 400 when the header names another resource or is malformed.
     */
    public static Long versionFromIfMatch(String resource, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String value = normalize(ifMatch);
        String prefix = "\"" + resource + "-";
        if (!value.startsWith(prefix) || !value.endsWith("\"")) {
            throw new ModuleException.BadRequest("If-Match does not name a " + resource + " version", "INVALID_IF_MATCH");
        }
        try {
            return Long.parseLong(value.substring(prefix.length(), value.length() - 1));
        }
        catch (NumberFormatException ex) {
            throw new ModuleException.BadRequest("If-Match does not name a " + resource + " version", "INVALID_IF_MATCH");
        }
    }

    /** Whether an {@code If-None-Match} header names this tag, so a 304 can be sent. */
    public static boolean matches(String etag, String ifNoneMatch) {
        return ifNoneMatch != null && normalize(etag).equals(normalize(ifNoneMatch));
    }

    /**
     * Drops the weak prefix and the encoding suffix compressing proxies (Caddy, nginx) add, as in
     * {@code W/"branding-7-zstd"}.
     */
    private static String normalize(String tag) {
        String value = tag.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        return ENCODING_SUFFIX.matcher(value).replaceFirst("\"");
    }
}
