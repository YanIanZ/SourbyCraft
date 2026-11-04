package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable fabric event.
 *
 * <p>The identifier is generated once, at publication, and is the idempotency key of the effect: a
 * consumer records it before applying the effect and acknowledges the stream entry afterwards, so an
 * at-least-once redelivery cannot duplicate the effect. The payload is a flat string map so an event
 * stays inspectable with {@code XRANGE} and never requires a deserialiser that can build arbitrary
 * objects.</p>
 *
 * @param id unique event id ({@code UUID})
 * @param type event type; unknown types are skipped by older consumers
 * @param schema payload schema version; bumped when fields change meaning
 * @param atMillis wall-clock publish time, for diagnosis only (never ordering)
 * @param source node that published it
 * @param payload bounded flat fields
 */
public record FabricEvent(String id, FabricEventType type, int schema, long atMillis, String source,
                          Map<String, String> payload) {

    public static final int SCHEMA = 1;
    public static final int MAX_FIELDS = 32;
    public static final int MAX_VALUE_LENGTH = 4096;

    public FabricEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        if (schema < 1) throw new IllegalArgumentException("schema " + schema);
        if (payload == null) payload = Map.of();
        if (payload.size() > MAX_FIELDS) throw new IllegalArgumentException("an event carries at most " + MAX_FIELDS + " fields");
        final Map<String, String> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : payload.entrySet()) {
            final String key = Objects.requireNonNull(entry.getKey(), "payload key");
            final String value = Objects.requireNonNull(entry.getValue(), "payload value");
            if (key.isEmpty() || key.length() > 64) throw new IllegalArgumentException("payload key length: " + key);
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("payload value of '" + key + "' is longer than " + MAX_VALUE_LENGTH);
            }
            copy.put(key, value);
        }
        payload = Collections.unmodifiableMap(copy);
    }

    public static FabricEvent of(final FabricEventType type, final String source, final Map<String, String> payload) {
        return new FabricEvent(UUID.randomUUID().toString(), type, SCHEMA, System.currentTimeMillis(), source, payload);
    }

    /** Stream fields: every header first, then the payload, in a stable order. */
    public Map<String, String> fields() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", this.id);
        fields.put("type", this.type.wireName());
        fields.put("schema", Integer.toString(this.schema));
        fields.put("at", Long.toString(this.atMillis));
        fields.put("source", this.source);
        this.payload.forEach((key, value) -> fields.put("p:" + key, value));
        return fields;
    }

    /** Decodes stream fields; {@code null} when a required field is missing or malformed. */
    public static FabricEvent from(final Map<String, String> fields) {
        final String id = fields.get("id");
        final FabricEventType type = FabricEventType.parse(fields.get("type"));
        if (id == null || type == null) return null;
        final int schema;
        try {
            schema = Integer.parseInt(fields.getOrDefault("schema", "1"));
        } catch (final NumberFormatException malformed) {
            return null;
        }
        final long at;
        try {
            at = Long.parseLong(fields.getOrDefault("at", "0"));
        } catch (final NumberFormatException malformed) {
            return null;
        }
        final Map<String, String> payload = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey().startsWith("p:") && entry.getKey().length() > 2) {
                payload.put(entry.getKey().substring(2), entry.getValue());
            }
        }
        try {
            return new FabricEvent(id, type, Math.max(1, schema), at, fields.getOrDefault("source", "unknown"), payload);
        } catch (final RuntimeException malformed) {
            return null;
        }
    }
}
