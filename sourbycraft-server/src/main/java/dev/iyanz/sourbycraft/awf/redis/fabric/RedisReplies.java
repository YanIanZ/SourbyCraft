package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reply decoding for the small RESP2 surface the fabric needs. */
final class RedisReplies {

    private RedisReplies() {}

    /** A bulk or simple-string reply as text, or {@code null}. */
    static String text(final Object reply) {
        return dev.iyanz.sourbycraft.awf.redis.RedisClient.text(reply);
    }

    /** A numeric reply, or {@code fallback} when the reply is not a number. */
    static long number(final Object reply, final long fallback) {
        if (reply instanceof Number number) return number.longValue();
        if (reply instanceof byte[] raw) {
            try {
                return Long.parseLong(new String(raw, java.nio.charset.StandardCharsets.US_ASCII));
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean isError(final Object reply) {
        return reply instanceof dev.iyanz.sourbycraft.awf.redis.RedisClient.RedisError;
    }

    static String errorText(final Object reply) {
        return reply instanceof Throwable throwable ? throwable.getMessage() : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(final Object reply) {
        if (reply instanceof List<?> list) return (List<Object>) list;
        return List.of();
    }

    /**
     * {@code HGETALL}'s flat array → map, keeping Redis order. Values are text; a {@code null}
     * value (missing key) yields an empty map.
     */
    static Map<String, String> map(final Object reply) {
        if (!(reply instanceof List<?> list) || list.isEmpty()) return Map.of();
        final Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            final String key = text(list.get(i));
            if (key != null) values.put(key, text(list.get(i + 1)));
        }
        return values;
    }

    /** A list of bulk strings, in order, skipping nulls. */
    static List<String> strings(final Object reply) {
        final List<Object> raw = list(reply);
        final List<String> values = new ArrayList<>(raw.size());
        for (final Object item : raw) {
            final String text = text(item);
            if (text != null) values.add(text);
        }
        return values;
    }
}
