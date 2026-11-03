package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity of one co-op operation: a server-issued id bound to the actor and to a digest of the
 * request.
 *
 * <p>Every guarded mutation and every {@link ProfileIntent} carries one. Replay is resolved by the id
 * before any revision check: the same id with the same digest returns the stored
 * {@link ProfileReceipt}; the same id with another digest is {@code INVALID_REQUEST}. The digest is
 * SHA-256 over the length-prefixed request fields ({@link #digest}), so the comparison does not depend
 * on object identity or on the order the caller rebuilt a map in.</p>
 *
 * @param operationId server-issued, never reused
 * @param kind which operation; stored as its {@link Kind#tag()}
 * @param actor the account acting (an offline administrator's operation names the administrator)
 * @param requestDigest 32 bytes, see {@link #digest}
 * @param createdAtMillis when the id was issued
 */
public record CoopOperation(UUID operationId, Kind kind, UUID actor, byte[] requestDigest, long createdAtMillis) {

    public static final int DIGEST_BYTES = 32;

    public CoopOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requestDigest, "requestDigest");
        if (requestDigest.length != DIGEST_BYTES) throw new IllegalArgumentException("request digest length");
        if (createdAtMillis < 0) throw new IllegalArgumentException("createdAtMillis");
        requestDigest = requestDigest.clone();
    }

    public static CoopOperation start(final Kind kind, final UUID actor, final byte[] requestDigest) {
        return new CoopOperation(UUID.randomUUID(), kind, actor, requestDigest, System.currentTimeMillis());
    }

    @Override
    public byte[] requestDigest() {
        return this.requestDigest.clone();
    }

    /** Whether {@code digest} is this operation's request digest (constant-time comparison). */
    public boolean sameRequest(final byte[] digest) {
        return digest != null && MessageDigest.isEqual(this.requestDigest, digest);
    }

    /**
     * SHA-256 over the parts, each prefixed by its length, so {@code ("ab","c")} and {@code ("a","bc")}
     * differ. {@code null} parts are encoded as length -1. Strings are UTF-8, UUIDs 16 bytes, longs 8.
     */
    public static byte[] digest(final Object... parts) {
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (final Object part : parts) {
                final byte[] bytes = bytesOf(part);
                sha.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                if (bytes != null) sha.update(bytes);
            }
            return sha.digest();
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("the JDK has no SHA-256", impossible);
        }
    }

    private static byte[] bytesOf(final Object part) {
        if (part == null) return null;
        if (part instanceof byte[] bytes) return bytes;
        if (part instanceof UUID uuid) {
            return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        }
        if (part instanceof Long value) return ByteBuffer.allocate(8).putLong(value).array();
        if (part instanceof Integer value) return ByteBuffer.allocate(4).putInt(value).array();
        if (part instanceof Boolean value) return new byte[] {(byte) (value ? 1 : 0)};
        if (part instanceof Enum<?> value) return value.name().getBytes(StandardCharsets.UTF_8);
        return part.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Operation kinds with stable numeric tags (never reordered or reused). An intent of a kind this
     * build does not know is refused on read. Kinds marked "later build" exist so their records decode;
     * no Build 51 command or API issues them.
     */
    public enum Kind {
        /** Create a profile, assign its name and the account slot. */
        CREATE_PROFILE(1),
        /** Rename an account-local alias; no profile metadata change. */
        RENAME_ALIAS(2),
        /** Administrative hold or release, or a status change (lifecycle). */
        SET_STATUS(3),
        /** Publish a member's snapshot (ordinary save or initialisation). */
        SAVE_MEMBER(4),
        /** Switch the account's active profile. */
        SWITCH_ACTIVE(5),
        /** Delete a profile (tombstone). */
        DELETE_PROFILE(6),
        /** Change the profile's world binding. */
        BIND_WORLD(7),
        /** Offline format migration. */
        MIGRATION(8),
        /** Resolve an account cleanup obligation at login. */
        CLEANUP(9),
        /** Login admission: the player's session claim {@code Q} is taken and the account's session generation advances. */
        LOGIN(10),
        /** Change an account's profile slot limit. */
        ACCOUNT_LIMIT(11),
        /** Build 52: create an invite ({@link CoopMembership#createInvite}). */
        INVITE_CREATE(20),
        /** Build 52: revoke, decline or expire an invite ({@link CoopMembership#closeInvite}). */
        INVITE_REVOKE(21),
        /** Build 52: accept an invite (admission: member row, head, account access, alias). */
        INVITE_ACCEPT(22),
        /** Build 52: remove a member (leave or executed kick): row dropped, head CONSUMED, salvage UNAVAILABLE. */
        MEMBER_REMOVE(23),
        /** Later build: change a member's role. */
        ROLE_CHANGE(24),
        /** Build 52: change the shared display label (never an account alias). */
        RENAME_DISPLAY(25),
        /** Build 52: open a kick vote. */
        VOTE_CREATE(26),
        /** Build 52: cast an approval in, or decline, a kick vote. */
        VOTE_CAST(27),
        /** Build 52: the one-way conversion of a solo profile to co-op. */
        CONVERT_COOP(28),
        /** Build 52: cancel or expire a kick vote (electorate changed, target left, expiry). */
        VOTE_CANCEL(29),
        /** Build 54: shared or personal bank, collection or contribution mutation (direct commit, permanent receipt). */
        SHARED_MUTATE(40),
        /** Build 54: shared/personal bank transfer within one profile, run as an intent with a permanent receipt. */
        BANK_TRANSFER(41),
        /** Reserved: salvage preparation (source consumption); Build 52 consumes the source inside {@link #MEMBER_REMOVE}. */
        SALVAGE_PREPARE(50),
        /** Build 54: salvage delivery into the claimant's head (one intent: head, salvage record, receipt). */
        SALVAGE_DELIVER(51);

        private final int tag;

        Kind(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        /**
         * Whether a direct commit of this kind leaves a permanent receipt (COOP-DESIGN §9 caps permanent receipts
         * for financial and delivery operations). The offline migration keeps none (one operation converts several
         * records of one resource); {@link #SAVE_MEMBER} and {@link #LOGIN} keep none because they run on every save
         * and every join: a replay is answered by the head revision and the session generation, and a million of
         * them would exhaust the per-resource receipt cap and refuse further saves.
         */
        public boolean permanentReceipt() {
            return switch (this) {
                case MIGRATION, SAVE_MEMBER, LOGIN -> false;
                default -> true;
            };
        }

        /**
         * Whether this kind's receipts are <em>permanent</em> (COOP-DESIGN §9): financial and delivery receipts are never
         * dropped by the per-resource retention that bounds the other kinds ({@link ProfileReceipt#RETAINED_PER_RESOURCE});
         * instead a resource holding {@link ProfileReceipt.Page#MAX_RECEIPTS_PER_RESOURCE} of them refuses the next such
         * operation ({@code CAPACITY_EXCEEDED}) before any write. Replay protection of money and items therefore does not
         * depend on how many other operations ran since.
         */
        public boolean permanent() {
            return switch (this) {
                case SHARED_MUTATE, BANK_TRANSFER, SALVAGE_PREPARE, SALVAGE_DELIVER -> true;
                default -> false;
            };
        }

        public static Kind fromTag(final int tag, final String where) throws IOException {
            for (final Kind kind : values()) {
                if (kind.tag == tag) return kind;
            }
            throw new IOException("operation kind tag " + tag + " in " + where + " is not known to this build");
        }
    }
}
