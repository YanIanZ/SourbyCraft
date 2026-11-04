package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 54. A co-op bank or collection change committed: a deposit, withdrawal, personal-bank posting,
 * contribution, interest posting or shared&lt;-&gt;personal transfer.
 *
 * <p><b>Timing.</b> Fired after the change is durable (its permanent receipt is written), never before, and never when
 * the commit was refused or its outcome is {@link ProfileErrorCode#UNKNOWN_OUTCOME}. <b>Thread.</b> Asynchronous
 * ({@link #isAsynchronous()} is true), fired on the profile service's co-op event thread, which is neither a region, an
 * entity nor the global tick thread; a handler must not touch world, entity or player state directly and must return
 * quickly. <b>Delivery.</b> Fired by the node that committed the change, once per commit. Not cancellable and not a
 * transaction: a crash between the commit and the firing loses the event, and a full event queue drops it with a WARN.
 * The bank ({@link AuroraProfiles#bank}) and the receipts are the source of truth, not the event stream.</p>
 */
public class ProfileBankTransactionEvent extends Event {

    /** What the change posted. */
    public enum Type {
        /** Shared bank credit. */
        DEPOSIT,
        /** Shared bank debit. */
        WITHDRAW,
        /** The actor's personal bank credit. */
        DEPOSIT_PERSONAL,
        /** The actor's personal bank debit. */
        WITHDRAW_PERSONAL,
        /** A collection credit with the actor's contribution ({@link #getCollection()}). */
        CONTRIBUTE,
        /** Interest credited to the shared balance and the personal banks. */
        INTEREST,
        /** A transfer between the shared and the actor's personal bank ({@link #getFrom()}, {@link #getTo()}). */
        TRANSFER
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID profileId;
    private final UUID actorId;
    private final Type type;
    private final @Nullable String collection;
    private final @Nullable ProfileBankAccount from;
    private final @Nullable ProfileBankAccount to;
    private final long amount;
    private final long sharedBalanceAfter;
    private final ProfileCurrency currency;
    private final UUID operationId;

    @ApiStatus.Internal
    public ProfileBankTransactionEvent(final UUID profileId, final UUID actorId, final Type type, final @Nullable String collection,
                                       final @Nullable ProfileBankAccount from, final @Nullable ProfileBankAccount to, final long amount,
                                       final long sharedBalanceAfter, final ProfileCurrency currency, final UUID operationId) {
        super(true);
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.type = Objects.requireNonNull(type, "type");
        this.collection = collection;
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.sharedBalanceAfter = sharedBalanceAfter;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.operationId = Objects.requireNonNull(operationId, "operationId");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The co-op profile. */
    public UUID getProfileId() {
        return this.profileId;
    }

    /** The member who made the change. */
    public UUID getActorId() {
        return this.actorId;
    }

    public Type getType() {
        return this.type;
    }

    /** The collection of a {@link Type#CONTRIBUTE}, else {@code null}. */
    public @Nullable String getCollection() {
        return this.collection;
    }

    /** The debited account of a {@link Type#TRANSFER}, else {@code null}. */
    public @Nullable ProfileBankAccount getFrom() {
        return this.from;
    }

    /** The credited account of a {@link Type#TRANSFER}, else {@code null}. */
    public @Nullable ProfileBankAccount getTo() {
        return this.to;
    }

    /** Minor units posted; for {@link Type#INTEREST} the total credited to the shared and personal banks together. */
    public long getAmount() {
        return this.amount;
    }

    /** The committed shared balance after the change. */
    public long getSharedBalanceAfter() {
        return this.sharedBalanceAfter;
    }

    /** The currency of the amounts. */
    public ProfileCurrency getCurrency() {
        return this.currency;
    }

    /** The operation, named by its permanent receipt. */
    public UUID getOperationId() {
        return this.operationId;
    }
}
