package io.github.ilyaslabs.foodstack.platform.mongo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites MongoDB connection strings. */
final class MongoUris {

    private static final Pattern URI = Pattern.compile("^(mongodb(?:\\+srv)?://)([^/?]*)(/[^?]*)?(\\?.*)?$");

    private MongoUris() {
    }

    /**
     * The same connection string with {@code database} as its path. When the string carries
     * credentials, the original path database (or {@code admin}) is kept as {@code authSource}, so
     * changing the database never changes where the user authenticates.
     */
    static String withDatabase(String uri, String database) {
        Matcher matcher = URI.matcher(uri);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a MongoDB connection string: " + redact(uri));
        }
        String scheme = matcher.group(1);
        String hosts = matcher.group(2);
        String path = matcher.group(3);
        String query = (matcher.group(4) != null) ? matcher.group(4).substring(1) : "";
        boolean hasCredentials = hosts.contains("@");
        if (hasCredentials && !query.contains("authSource=")) {
            String originalDb = (path != null && path.length() > 1) ? path.substring(1) : "admin";
            query = query.isEmpty() ? "authSource=" + originalDb : query + "&authSource=" + originalDb;
        }
        return scheme + hosts + "/" + database + (query.isEmpty() ? "" : "?" + query);
    }

    static String redact(String uri) {
        return uri.replaceAll("//[^@/]*@", "//***@");
    }
}
