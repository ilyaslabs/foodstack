package io.github.ilyaslabs.foodstack.review.core.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The profanity filter of review texts and replies (PRD RV-3): whole words from a built-in list in
 * English, Arabic and Urdu, plus the operator's own words, are masked with stars. Matching ignores
 * case and accepts letters stretched by repetition ({@code baaad}).
 */
public final class BlockedWords {

    private static final String BUILT_IN = "/review/blocked-words.txt";

    private final Pattern pattern;

    private BlockedWords(Collection<String> words) {
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(words));
        // longer words first, so a phrase wins over a word inside it
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder alternatives = new StringBuilder();
        for (String word : sorted) {
            if (!alternatives.isEmpty()) {
                alternatives.append('|');
            }
            alternatives.append(stretchable(word));
        }
        this.pattern = sorted.isEmpty() ? null
                : Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** The built-in list with the operator's extra words. */
    public static BlockedWords withBuiltIn(Collection<String> extra) {
        Set<String> words = new LinkedHashSet<>(read());
        for (String word : extra) {
            String clean = normalise(word);
            if (!clean.isEmpty()) {
                words.add(clean);
            }
        }
        return new BlockedWords(words);
    }

    /** Only the given words; for tests. */
    public static BlockedWords of(Collection<String> words) {
        return new BlockedWords(words.stream().map(BlockedWords::normalise).filter((w) -> !w.isEmpty()).toList());
    }

    /** The text with every blocked word replaced by stars of the same length. */
    public Result mask(String text) {
        if (text == null || this.pattern == null) {
            return new Result(text, false);
        }
        Matcher matcher = this.pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            matcher.appendReplacement(out, "*".repeat(matcher.group().codePointCount(0, matcher.group().length())));
        }
        matcher.appendTail(out);
        return new Result(out.toString(), found);
    }

    /**
     * @param text the masked text
     * @param flagged whether anything was masked
     */
    public record Result(String text, boolean flagged) {
    }

    private static String stretchable(String word) {
        StringBuilder regex = new StringBuilder();
        word.codePoints().forEach((cp) -> {
            if (Character.isWhitespace(cp)) {
                regex.append("\\s+");
            }
            else {
                regex.append(Pattern.quote(new String(Character.toChars(cp)))).append('+');
            }
        });
        return regex.toString();
    }

    private static String normalise(String word) {
        return (word == null) ? "" : word.strip().toLowerCase(Locale.ROOT);
    }

    private static List<String> read() {
        try (InputStream in = BlockedWords.class.getResourceAsStream(BUILT_IN)) {
            if (in == null) {
                return List.of();
            }
            List<String> words = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String clean = normalise(line);
                    if (!clean.isEmpty() && !clean.startsWith("#")) {
                        words.add(clean);
                    }
                }
            }
            return words;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
