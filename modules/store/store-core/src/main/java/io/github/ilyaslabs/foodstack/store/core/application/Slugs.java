package io.github.ilyaslabs.foodstack.store.core.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Readable web addresses for branches, such as {@code spice-route-gulberg}. */
public final class Slugs {

    /** Longest slug; long enough for a brand and a branch name. */
    public static final int MAX_LENGTH = 60;

    private static final Pattern VALID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private static final Pattern NOT_ALLOWED = Pattern.compile("[^a-z0-9]+");

    private Slugs() {
    }

    /**
     * A slug from a brand and branch name: accents removed, lower case, words joined by hyphens.
     * Names with no Latin letters or digits (an Urdu-only name, say) become {@code store}.
     */
    public static String of(String brandName, String branchName) {
        String text = (branchName == null || branchName.isBlank()) ? brandName : brandName + " " + branchName;
        String ascii = MARKS.matcher(Normalizer.normalize((text != null) ? text : "", Normalizer.Form.NFKD)).replaceAll("");
        String slug = NOT_ALLOWED.matcher(ascii.toLowerCase(Locale.ROOT).replace("&", " and ")).replaceAll("-");
        slug = trim(slug);
        return slug.isEmpty() ? "store" : slug;
    }

    /** {@code base} with a number on the end, for when {@code base} is taken: {@code spice-route-2}. */
    public static String numbered(String base, int number) {
        String suffix = "-" + number;
        return trim(base.substring(0, Math.min(base.length(), MAX_LENGTH - suffix.length()))) + suffix;
    }

    /** Whether a slug someone typed is acceptable: lower-case letters, digits and single hyphens. */
    public static boolean isValid(String slug) {
        return slug != null && slug.length() >= 3 && slug.length() <= MAX_LENGTH && VALID.matcher(slug).matches()
                && !looksLikeId(slug);
    }

    /** Branch ids are UUIDs; a slug may not look like one, so a link can always be read either way. */
    public static boolean looksLikeId(String key) {
        return key != null && key.length() == 36 && key.charAt(8) == '-' && key.charAt(13) == '-' && key.charAt(18) == '-'
                && key.charAt(23) == '-';
    }

    private static String trim(String slug) {
        String cut = slug.substring(0, Math.min(slug.length(), MAX_LENGTH));
        int start = 0;
        int end = cut.length();
        while (start < end && cut.charAt(start) == '-') {
            start++;
        }
        while (end > start && cut.charAt(end - 1) == '-') {
            end--;
        }
        return cut.substring(start, end);
    }
}
