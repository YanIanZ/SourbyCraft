package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One immutable entry of the MultiProfile audit log (Build 55).
 *
 * <p>An entry is recorded by the server <em>after</em> the durable outcome of an operation (a committed switch,
 * create, delete, lock, archive, co-op membership change, bank posting, salvage delivery) or after a refusal
 * (a switch veto, a cooldown refusal, a failed switch). Recording never changes the outcome it describes: an
 * audit write that fails is counted and logged, the operation stays committed or refused as it was. The log is
 * node-local, bounded and retained by {@code aurora.awf.multiprofile.audit.retention-days}; it is not the
 * recovery journal (the store's intents and transitions are).</p>
 *
 * @param sequence the node-local sequence number; strictly increasing while the server runs
 * @param atMillis wall-clock time of the record (this node's clock)
 * @param kind a short stable kind id, lowercase with dots, e.g. {@code switch.committed}, {@code switch.refused},
 *     {@code profile.created}, {@code profile.deleted}, {@code profile.archived}, {@code coop.invite},
 *     {@code bank.deposit}, {@code salvage.delivered}
 * @param profileId the profile the entry is about (the target of a switch), or {@code null} when none applies
 * @param actorId the account that asked (the switching player, the inviter, the depositor), or {@code null}
 * @param operationId the operation or transition id, when the operation had one
 * @param success whether the operation committed; {@code false} for a refusal or a failure
 * @param errorCode the error code of a refusal or failure, else {@code null}
 * @param detail a short player-safe description (at most {@value #MAX_DETAIL} characters)
 */
@NullMarked
public record ProfileAuditEntry(long sequence, long atMillis, String kind, @Nullable UUID profileId, @Nullable UUID actorId,
                                @Nullable UUID operationId, boolean success, @Nullable ProfileErrorCode errorCode,
                                String detail) {

    /** Longest detail text; longer ones are cut by the server before they are recorded. */
    public static final int MAX_DETAIL = 256;

    public ProfileAuditEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
        if (kind.isEmpty() || kind.length() > 64) throw new IllegalArgumentException("audit kind: " + kind);
        if (detail.length() > MAX_DETAIL) detail = detail.substring(0, MAX_DETAIL);
        if (success && errorCode != null) throw new IllegalArgumentException("a successful entry has no error code");
    }
}
