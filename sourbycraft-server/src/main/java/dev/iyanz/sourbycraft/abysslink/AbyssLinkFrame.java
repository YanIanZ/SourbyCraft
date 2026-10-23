package dev.iyanz.sourbycraft.abysslink;

import java.util.Objects;

/**
 * An owned, immutable AbyssLink control-plane frame. Request IDs preserve all 64 wire bits.
 *
 * <p>The payload is copied on construction and again by {@link #payload()}, so neither the caller's
 * array nor an array returned to a caller can change frame state. {@link #wirePayload()} hands the
 * backing array to the encoder only; it is package-private, read-only by contract and never
 * retained by the codec.</p>
 *
 * <p>The header's minor byte is carried verbatim as an {@code int} in {@code 0..255}; the record
 * rejects values outside that range because a single wire byte cannot express them. Compatibility
 * filtering is not the record's job: {@link AbyssLinkSession} currently rejects any non-zero minor
 * as unnegotiated before dispatch, so raising the accepted minor later is a session change, not a
 * frame or codec change.</p>
 */
public record AbyssLinkFrame(int minor, int type, long requestId, byte[] payload) {

    /** Largest payload the v1 length field is allowed to describe. */
    public static final int MAX_PAYLOAD = 65_536;

    /** Minor version used by the default constructor and v1's unnegotiated baseline. */
    public static final int MINOR = 0;

    public AbyssLinkFrame {
        if (minor < 0 || minor > 255) {
            throw new IllegalArgumentException("Invalid minor version");
        }
        if (!validType(type)) {
            throw new IllegalArgumentException("Unknown message type");
        }
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Payload too large");
        }
        payload = payload.clone();
    }

    public AbyssLinkFrame(int type, long requestId, byte[] payload) {
        this(MINOR, type, requestId, payload);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    // Package-private access avoids another allocation in the encoder; callers cannot mutate it.
    byte[] wirePayload() {
        return payload;
    }

    public static boolean validType(int type) {
        return (type >= 1 && type <= 7) || type == 255;
    }
}
