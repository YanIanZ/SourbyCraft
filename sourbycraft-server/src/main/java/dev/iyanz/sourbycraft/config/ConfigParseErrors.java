package dev.iyanz.sourbycraft.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a config parse failure into a line the operator can act on.
 *
 * <p>nightconfig's {@code ParsingException} carries the reason but not the position ("Invalid integer
 * value: sourby"), which on a file the seeder wrote two builds ago is not enough to find the line.
 * The offending token is the end of that message, so this looks for it in the file and reports the
 * first matching line -- as "near line N", because a token can also appear in a comment or in an
 * unrelated value. Nothing here reads or writes config values, and the file text is truncated to a
 * short snippet rather than copied into the log.</p>
 */
public final class ConfigParseErrors {

    /** Longest snippet kept from the offending line; enough to recognise the key, not a file paste. */
    static final int SNIPPET_LIMIT = 120;
    /** Config files are small; a larger file is reported without a line lookup rather than read whole. */
    static final long LOOKUP_LIMIT_BYTES = 1L << 20;
    /** The tail of a nightconfig value error: "Invalid integer value: sourby", "Invalid value: x". */
    private static final Pattern VALUE_ERROR =
        Pattern.compile(".*(?:invalid|unexpected|unknown)\\b[^:]*:\\s*([A-Za-z0-9_.+\\-]{1,64})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.+\\-]{1,64}");

    private ConfigParseErrors() {
    }

    /**
     * {@code file} followed by the line the failure points at (when it can be found) and the parser's
     * own message, e.g.
     * {@code sourbycraft_config/aurora.yml (near line 12: `worlds: sourby`): Invalid integer value: sourby}.
     */
    public static String describe(final Path file, final Throwable failure) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(failure, "failure");
        final String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        final String token = offendingToken(message);
        if (token == null) return file + " (" + message + ")";
        final Location location = locate(file, token);
        if (location == null) return file + " (" + message + ")";
        return file + " (near line " + location.line() + ": `" + location.snippet() + "`): " + message;
    }

    /** The bare token a value error ends with, or {@code null} when the message has no usable one. */
    static String offendingToken(final String message) {
        final Matcher matcher = VALUE_ERROR.matcher(message);
        if (!matcher.matches()) return null;
        final String token = matcher.group(1);
        return TOKEN.matcher(token).matches() ? token : null;
    }

    private record Location(int line, String snippet) {
    }

    private static Location locate(final Path file, final String token) {
        final List<String> lines;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > LOOKUP_LIMIT_BYTES) return null;
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            return null;
        }
        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index);
            if (!containsToken(line, token)) continue;
            return new Location(index + 1, snippet(line));
        }
        return null;
    }

    /** Whole-token match: {@code sourby} matches {@code worlds = sourby}, not {@code sourby_old}. */
    private static boolean containsToken(final String line, final String token) {
        int from = 0;
        while (true) {
            final int at = line.indexOf(token, from);
            if (at < 0) return false;
            final int end = at + token.length();
            final boolean leftFree = at == 0 || !isTokenChar(line.charAt(at - 1));
            final boolean rightFree = end == line.length() || !isTokenChar(line.charAt(end));
            if (leftFree && rightFree) return true;
            from = at + 1;
        }
    }

    private static boolean isTokenChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+';
    }

    private static String snippet(final String line) {
        final String trimmed = line.strip();
        final String flat = trimmed.replaceAll("\\p{Cntrl}", "\\uFFFD");
        return flat.length() <= SNIPPET_LIMIT ? flat : flat.substring(0, SNIPPET_LIMIT - 1) + "\u2026";
    }
}
