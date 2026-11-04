package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, deterministic binary codec for {@link ProfileState}.
 *
 * <p>Design rules:</p>
 * <ul>
 *   <li>Every length is bounded before it is allocated; a corrupt or hostile file cannot ask for an
 *       arbitrary array. There is no reflective deserialisation: only the fields below are read.</li>
 *   <li>The encoding is stable for a schema: a snapshot written by an older build decodes to the same
 *       values. A schema change increments {@link ProfileState#SCHEMA} and is handled explicitly.</li>
 *   <li>Item bytes are opaque. The platform adapter encodes and decodes them, so item components and
 *       data components survive without a lossy conversion here.</li>
 * </ul>
 */
public final class ProfileStateCodec {

    private static final int MAGIC = 0x41574650; // "AWFP"
    private static final int FORMAT = 1;
    private static final int MAX_TEXT = 4096;
    private static final int MAX_TOTAL = ProfileSnapshot.MAX_STATE_BYTES;

    private ProfileStateCodec() {}

    /** A payload that is not a valid profile state; never a partially decoded object. */
    public static final class ProfileStateFormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ProfileStateFormatException(final String message) {
            super(message);
        }

        public ProfileStateFormatException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    public static byte[] encode(final ProfileState state) {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeByte(FORMAT);
            out.writeInt(state.schema());
            writeItems(out, state.inventory());
            writeItems(out, state.armor());
            writeItems(out, state.offhand());
            writeItems(out, state.enderChest());
            out.writeInt(state.level());
            out.writeFloat(state.experience());
            out.writeDouble(state.health());
            out.writeInt(state.food());
            out.writeFloat(state.saturation());
            out.writeInt(state.effects().size());
            for (final ProfileState.Effect effect : state.effects()) {
                writeText(out, effect.typeKey());
                out.writeInt(effect.durationTicks());
                out.writeInt(effect.amplifier());
                out.writeByte((effect.ambient() ? 1 : 0) | (effect.particles() ? 2 : 0) | (effect.icon() ? 4 : 0));
            }
            writeLocation(out, state.spawn());
            writeLocation(out, state.returnPoint());
            final Map<String, byte[]> adapters = state.adapters();
            out.writeInt(adapters.size());
            for (final Map.Entry<String, byte[]> adapter : adapters.entrySet()) {
                writeText(out, adapter.getKey());
                out.writeInt(adapter.getValue().length);
                out.write(adapter.getValue());
            }
        } catch (final IOException impossible) {
            throw new IllegalStateException("writing to memory failed", impossible);
        }
        final byte[] encoded = buffer.toByteArray();
        if (encoded.length > MAX_TOTAL) {
            throw new ProfileStateFormatException("profile state is " + encoded.length + " bytes; the limit is " + MAX_TOTAL);
        }
        return encoded;
    }

    public static ProfileState decode(final byte[] encoded) {
        if (encoded == null || encoded.length < 8 || encoded.length > MAX_TOTAL) {
            throw new ProfileStateFormatException("profile state length " + (encoded == null ? "null" : encoded.length));
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) throw new ProfileStateFormatException("not a profile state payload");
            final int format = in.readUnsignedByte();
            if (format != FORMAT) throw new ProfileStateFormatException("profile state format " + format);
            final int schema = in.readInt();
            if (schema < 1 || schema > ProfileState.SCHEMA) {
                throw new ProfileStateFormatException("unsupported profile state schema " + schema);
            }
            final List<ProfileState.Item> inventory = readItems(in);
            final List<ProfileState.Item> armor = readItems(in);
            final List<ProfileState.Item> offhand = readItems(in);
            final List<ProfileState.Item> enderChest = readItems(in);
            final int level = in.readInt();
            final float experience = in.readFloat();
            final double health = in.readDouble();
            final int food = in.readInt();
            final float saturation = in.readFloat();
            final int effectCount = count(in, ProfileState.MAX_EFFECTS, "effects");
            final List<ProfileState.Effect> effects = new ArrayList<>(effectCount);
            for (int i = 0; i < effectCount; i++) {
                final String type = readText(in);
                final int duration = in.readInt();
                final int amplifier = in.readInt();
                final int flags = in.readUnsignedByte();
                effects.add(new ProfileState.Effect(type, duration, amplifier, (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0));
            }
            final ProfileState.Location spawn = readLocation(in);
            final ProfileState.Location returnPoint = readLocation(in);
            final int adapterCount = count(in, ProfileState.MAX_ADAPTERS, "adapters");
            final Map<String, byte[]> adapters = new LinkedHashMap<>();
            for (int i = 0; i < adapterCount; i++) {
                final String id = readText(in);
                adapters.put(id, readBytes(in, 2 * 1024 * 1024));
            }
            if (in.available() != 0) {
                throw new ProfileStateFormatException("trailing bytes after profile state");
            }
            return new ProfileState(schema, inventory, armor, offhand, enderChest, level, experience, health, food,
                saturation, effects, spawn, returnPoint, adapters);
        } catch (final EOFException truncated) {
            throw new ProfileStateFormatException("profile state is truncated", truncated);
        } catch (final IOException failed) {
            throw new ProfileStateFormatException("profile state could not be read: " + failed.getMessage(), failed);
        } catch (final IllegalArgumentException invalid) {
            throw invalid instanceof ProfileStateFormatException format ? format
                : new ProfileStateFormatException("profile state is invalid: " + invalid.getMessage(), invalid);
        }
    }

    /** Hex SHA-256 of the encoded state, used by {@link ProfileSnapshot}. */
    public static String checksum(final byte[] encoded) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("the JDK has no SHA-256", impossible);
        }
    }

    private static void writeItems(final DataOutputStream out, final List<ProfileState.Item> items) throws IOException {
        out.writeInt(items.size());
        for (final ProfileState.Item item : items) {
            out.writeInt(item.slot());
            final byte[] data = item.data();
            out.writeInt(data.length);
            out.write(data);
        }
    }

    private static List<ProfileState.Item> readItems(final DataInputStream in) throws IOException {
        final int count = count(in, ProfileState.MAX_ITEMS, "items");
        final List<ProfileState.Item> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int slot = in.readInt();
            items.add(new ProfileState.Item(slot, readBytes(in, 1_048_576)));
        }
        return items;
    }

    private static void writeLocation(final DataOutputStream out, final ProfileState.Location location) throws IOException {
        out.writeBoolean(location != null);
        if (location == null) return;
        writeText(out, location.world());
        out.writeDouble(location.x());
        out.writeDouble(location.y());
        out.writeDouble(location.z());
        out.writeFloat(location.yaw());
        out.writeFloat(location.pitch());
    }

    private static ProfileState.Location readLocation(final DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        return new ProfileState.Location(readText(in), in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(),
            in.readFloat());
    }

    private static void writeText(final DataOutputStream out, final String text) throws IOException {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) throw new IllegalArgumentException("text too long");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) throw new ProfileStateFormatException("text length " + length);
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("text truncated");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(final DataInputStream in, final int max) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > max) throw new ProfileStateFormatException("byte length " + length);
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("bytes truncated");
        return bytes;
    }

    private static int count(final DataInputStream in, final int max, final String what) throws IOException {
        final int count = in.readInt();
        if (count < 0 || count > max) throw new ProfileStateFormatException(what + " count " + count);
        return count;
    }
}
