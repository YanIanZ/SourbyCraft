package dev.iyanz.sourbycraft.abysslink;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded AbyssLink TLV primitives: a one-byte field ID, a big-endian unsigned 16-bit value length,
 * then the value bytes.
 *
 * <p>{@link #decode} keeps the wire order, rejects duplicate IDs and any field whose value is
 * truncated, and returns an unmodifiable map. Every ID — including IDs a schema does not know — is
 * retained there, so schema consumers read the fields they know and ignore the rest; nothing is
 * silently dropped or reordered. The total payload is bounded by
 * {@link AbyssLinkFrame#MAX_PAYLOAD}, which {@link #encode} enforces as well, so outbound and
 * inbound bounds are symmetric.</p>
 *
 * <p>Text uses strict UTF-8 in both directions: malformed byte sequences and unencodable UTF-16
 * surrogates are rejected instead of being replaced. Integer, UUID and boolean helpers require
 * exact widths; integers are signed big endian, UUIDs are two big-endian 64-bit halves and booleans
 * are exactly {@code 0} or {@code 1}.</p>
 */
public final class AbyssLinkTlv {

    /** Largest value a single big-endian unsigned 16-bit length can describe. */
    private static final int MAX_FIELD_LENGTH = 65_535;

    private AbyssLinkTlv() {
    }

    public static Map<Integer, byte[]> decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > AbyssLinkFrame.MAX_PAYLOAD) {
            throw new IllegalArgumentException("TLV payload too large");
        }
        final Map<Integer, byte[]> fields = new LinkedHashMap<>();
        final ByteBuffer input = ByteBuffer.wrap(payload);
        while (input.hasRemaining()) {
            if (input.remaining() < 3) {
                throw new IllegalArgumentException("Truncated TLV header");
            }
            final int id = input.get() & 255;
            final int length = input.getShort() & 65535;
            if (length > input.remaining()) {
                throw new IllegalArgumentException("Truncated TLV value");
            }
            if (fields.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate TLV field");
            }
            final byte[] value = new byte[length];
            input.get(value);
            fields.put(id, value);
        }
        return Collections.unmodifiableMap(fields);
    }

    public static byte[] encode(Map<Integer, byte[]> fields) {
        Objects.requireNonNull(fields, "fields");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (final Map.Entry<Integer, byte[]> field : fields.entrySet()) {
            final Integer id = Objects.requireNonNull(field.getKey(), "TLV field id");
            final byte[] value = Objects.requireNonNull(field.getValue(), "TLV field value");
            if (id < 0 || id > 255 || value.length > MAX_FIELD_LENGTH) {
                throw new IllegalArgumentException("Invalid TLV field");
            }
            if (output.size() + 3 + value.length > AbyssLinkFrame.MAX_PAYLOAD) {
                throw new IllegalArgumentException("TLV payload too large");
            }
            output.write(id);
            output.write(value.length >>> 8);
            output.write(value.length & 255);
            output.writeBytes(value);
        }
        return output.toByteArray();
    }

    public static byte[] bytes(Map<Integer, byte[]> fields, int id, int length) {
        final byte[] value = required(fields, id);
        if (value.length != length) {
            throw new IllegalArgumentException("Incorrect TLV field length");
        }
        return value.clone();
    }

    private static byte[] required(Map<Integer, byte[]> fields, int id) {
        Objects.requireNonNull(fields, "fields");
        final byte[] value = fields.get(id);
        if (value == null) {
            throw new IllegalArgumentException("Missing TLV field " + id);
        }
        return value;
    }

    public static String string(Map<Integer, byte[]> fields, int id, int maxBytes) {
        final byte[] value = required(fields, id);
        if (maxBytes < 0 || value.length > maxBytes) {
            throw new IllegalArgumentException("TLV string exceeds limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Malformed TLV UTF-8", e);
        }
    }

    public static boolean bool(Map<Integer, byte[]> fields, int id) {
        final int value = u8(fields, id);
        if (value > 1) {
            throw new IllegalArgumentException("Invalid TLV boolean");
        }
        return value == 1;
    }

    public static int u8(Map<Integer, byte[]> fields, int id) {
        return bytes(fields, id, 1)[0] & 255;
    }

    public static int unsignedShort(Map<Integer, byte[]> fields, int id) {
        return ByteBuffer.wrap(bytes(fields, id, 2)).getShort() & 65535;
    }

    public static int i32(Map<Integer, byte[]> fields, int id) {
        return ByteBuffer.wrap(bytes(fields, id, 4)).getInt();
    }

    public static long i64(Map<Integer, byte[]> fields, int id) {
        return ByteBuffer.wrap(bytes(fields, id, 8)).getLong();
    }

    public static UUID uuid(Map<Integer, byte[]> fields, int id) {
        final ByteBuffer value = ByteBuffer.wrap(bytes(fields, id, 16));
        return new UUID(value.getLong(), value.getLong());
    }

    public static byte[] value(String value) {
        Objects.requireNonNull(value, "value");
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Malformed UTF-16 string", e);
        }
    }

    public static byte[] value(boolean value) {
        return new byte[]{(byte) (value ? 1 : 0)};
    }

    public static byte[] value(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    public static byte[] value(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    public static byte[] value(UUID value) {
        Objects.requireNonNull(value, "value");
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }

    public static byte[] u8(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Invalid u8");
        }
        return new byte[]{(byte) value};
    }

    public static byte[] u16(int value) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException("Invalid u16");
        }
        return ByteBuffer.allocate(2).putShort((short) value).array();
    }
}
