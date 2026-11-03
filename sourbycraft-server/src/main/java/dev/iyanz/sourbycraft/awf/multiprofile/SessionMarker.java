package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable per-account session marker of authoritative login (Build 53, D3): "a session of generation
 * {@code generation} is open" from the login until the committed quit or shutdown capture closes it, plus the
 * <em>vanilla provenance</em>: the profile and membership epoch whose state this node's vanilla playerdata of the
 * player is known to hold, and when that was last established.
 *
 * <p>At the next authoritative login the engine reads it before the gate lifts: a marker that is still
 * {@code open} for the previous session (a crash, {@code kill -9}, a shutdown that missed its deadline, a save that
 * was refused), a marker whose generation is not the previous session's (sessions ran under a build or a setting
 * that did not maintain it), or no marker at all (the upgrade from Build 52) means the last session did not commit
 * its final state. Then, and only then, the engine compares the player's vanilla playerdata with the committed
 * head and adopts the vanilla state once when it is newer and its provenance is the active profile (see
 * {@code ProfileEngine#loginSource}).</p>
 *
 * <p>Provenance is recorded only when the vanilla file and the head are known to hold the same state: after a
 * lockstep save (the server saved the player's playerdata and the engine published that same capture), after a
 * login adoption, and at the committed quit. It is cleared ({@code null}) at every login until the first such sync.
 * A marker without provenance never leads to an adoption, except through the upgrade rule for a marker that is
 * absent or of another generation, where Build 52's semantics (vanilla playerdata holds the active profile's live
 * state) are assumed.</p>
 *
 * <p>Lifecycle: the admission of an authoritative login carries the previous session's marker to the new generation
 * unchanged (open flag and provenance kept), so a marker of another generation can only mean sessions that ran without
 * authoritative login; the login then writes it open with the provenance cleared when it restores the head, or open with
 * the provenance set once an adoption committed (a refused adoption leaves it as carried, so the next login retries);
 * a switch clears the provenance before it applies the target state; a corrupt marker is replaced at admission by an
 * open one without provenance (WARN), which never adopts.</p>
 *
 * <p>Stored outside the account record so the store level stays at Build 52: Build 52 never reads it. The node
 * is recorded because vanilla playerdata is per server: provenance established on another node says nothing about
 * this node's file.</p>
 *
 * @param playerId the account
 * @param generation the account's session generation the marker describes
 * @param open whether that session is still open (no committed final capture)
 * @param node the node whose vanilla playerdata the provenance describes
 * @param vanillaProfile the profile whose state the vanilla playerdata held at {@code syncedAtMillis}, or {@code null}
 * @param vanillaEpoch the membership epoch of that profile, or {@code null} with {@code vanillaProfile}
 * @param syncedAtMillis when the provenance was established (wall clock of the writer), {@code 0} when none
 */
public record SessionMarker(UUID playerId, long generation, boolean open, String node, UUID vanillaProfile, UUID vanillaEpoch,
                            long syncedAtMillis) {

    private static final int MAGIC = 0x4157534D; // "AWSM"
    private static final int FORMAT = 1;
    /** Longest node id stored; longer ones are truncated (the comparison is by prefix equality then). */
    public static final int MAX_NODE_CHARS = 128;
    /** Bound of the encoded marker. */
    public static final int MAX_BYTES = 512;

    public SessionMarker {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(node, "node");
        if (generation < 0L) throw new IllegalArgumentException("generation " + generation);
        if ((vanillaProfile == null) != (vanillaEpoch == null)) {
            throw new IllegalArgumentException("vanilla provenance names a profile and its epoch together or neither");
        }
        if (syncedAtMillis < 0L) throw new IllegalArgumentException("syncedAtMillis " + syncedAtMillis);
        if (node.length() > MAX_NODE_CHARS) node = node.substring(0, MAX_NODE_CHARS);
    }

    /** An open session of {@code generation} on {@code node}, provenance unknown. */
    public static SessionMarker opened(final UUID playerId, final long generation, final String node) {
        return new SessionMarker(playerId, generation, true, node, null, null, 0L);
    }

    /** This marker with the vanilla playerdata of {@code node} known to hold {@code (profile, epoch)} at {@code nowMillis}. */
    public SessionMarker synced(final UUID profile, final UUID epoch, final String node, final long nowMillis) {
        return new SessionMarker(this.playerId, this.generation, this.open, node, Objects.requireNonNull(profile, "profile"),
            Objects.requireNonNull(epoch, "epoch"), nowMillis);
    }

    /** This marker open (a session of its generation is running). */
    public SessionMarker opened() {
        return new SessionMarker(this.playerId, this.generation, true, this.node, this.vanillaProfile, this.vanillaEpoch,
            this.syncedAtMillis);
    }

    /** This marker carried to a new session generation, open flag and provenance kept (written at admission). */
    public SessionMarker atGeneration(final long generation) {
        return new SessionMarker(this.playerId, generation, this.open, this.node, this.vanillaProfile, this.vanillaEpoch,
            this.syncedAtMillis);
    }

    /** This marker with the vanilla provenance cleared (a switch is about to apply another profile's state). */
    public SessionMarker withoutProvenance() {
        return new SessionMarker(this.playerId, this.generation, this.open, this.node, null, null, 0L);
    }

    /** A closed marker of {@code generation} with no provenance: the next login restores the committed head (operator reset). */
    public static SessionMarker reset(final UUID playerId, final long generation, final String node) {
        return new SessionMarker(playerId, generation, false, node, null, null, 0L);
    }

    /** This marker closed: the session's final capture was committed. */
    public SessionMarker closed() {
        return new SessionMarker(this.playerId, this.generation, false, this.node, this.vanillaProfile, this.vanillaEpoch,
            this.syncedAtMillis);
    }

    /** Whether the provenance names exactly {@code (profile, epoch)} on {@code node}. */
    public boolean provenanceIs(final UUID profile, final UUID epoch, final String node) {
        return this.vanillaProfile != null && this.vanillaProfile.equals(profile) && this.vanillaEpoch.equals(epoch)
            && this.node.equals(node.length() > MAX_NODE_CHARS ? node.substring(0, MAX_NODE_CHARS) : node);
    }

    public byte[] encode() {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeByte(FORMAT);
            out.writeLong(this.playerId.getMostSignificantBits());
            out.writeLong(this.playerId.getLeastSignificantBits());
            out.writeLong(this.generation);
            out.writeBoolean(this.open);
            final byte[] node = this.node.getBytes(StandardCharsets.UTF_8);
            out.writeShort(node.length);
            out.write(node);
            out.writeBoolean(this.vanillaProfile != null);
            if (this.vanillaProfile != null) {
                out.writeLong(this.vanillaProfile.getMostSignificantBits());
                out.writeLong(this.vanillaProfile.getLeastSignificantBits());
                out.writeLong(this.vanillaEpoch.getMostSignificantBits());
                out.writeLong(this.vanillaEpoch.getLeastSignificantBits());
            }
            out.writeLong(this.syncedAtMillis);
        } catch (final IOException impossible) {
            throw new IllegalStateException("writing to memory failed", impossible);
        }
        return buffer.toByteArray();
    }

    /** Decodes a marker; {@code where} names the record for the error. */
    public static SessionMarker decode(final byte[] bytes, final UUID expectedPlayer, final String where) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("session marker " + where + " has an invalid length");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("session marker " + where + " has no marker magic");
            final int format = in.readUnsignedByte();
            if (format != FORMAT) throw new IOException("session marker " + where + " is format " + format + "; this build reads " + FORMAT);
            final UUID player = new UUID(in.readLong(), in.readLong());
            if (!player.equals(expectedPlayer)) {
                throw new IOException("session marker " + where + " names " + player + ", expected " + expectedPlayer);
            }
            final long generation = in.readLong();
            final boolean open = in.readBoolean();
            final int nodeLength = in.readUnsignedShort();
            if (nodeLength > MAX_NODE_CHARS * 4) throw new IOException("session marker " + where + " has an oversized node id");
            final byte[] node = new byte[nodeLength];
            in.readFully(node);
            UUID profile = null;
            UUID epoch = null;
            if (in.readBoolean()) {
                profile = new UUID(in.readLong(), in.readLong());
                epoch = new UUID(in.readLong(), in.readLong());
            }
            final long synced = in.readLong();
            if (in.available() != 0) throw new IOException("session marker " + where + " has trailing bytes");
            return new SessionMarker(player, generation, open, new String(node, StandardCharsets.UTF_8), profile, epoch, synced);
        } catch (final EOFException truncated) {
            throw new IOException("session marker " + where + " is truncated", truncated);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("session marker " + where + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    public String describe() {
        return "generation " + this.generation + (this.open ? " open" : " closed") + ", vanilla on " + this.node + " = "
            + (this.vanillaProfile == null ? "unknown" : this.vanillaProfile + "/" + this.vanillaEpoch + " at " + this.syncedAtMillis);
    }
}
