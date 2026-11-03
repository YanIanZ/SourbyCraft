package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A profile's shared state ({@code S}, format-2 record type 6): the shared bank, the members' personal
 * bank subaccounts, collections, per-member contributions and pending transfer reservations.
 *
 * <p>This is an immutable value. It guarantees nothing about concurrency by itself: the CAS guarantee
 * belongs to the guarded repository commit, which compares the stored {@code S} with the guard's
 * expected revision in the same atomic step as it installs the after-image this value encodes.
 * {@code revision} advances exactly once per committed mutation; the mutation methods below compute
 * the after-image at {@code S + 1} and the repository refuses it when {@code S} moved. Its scope is
 * bank, collections and contributions; it is not an implementation of auctions, minions or upgrades.</p>
 *
 * <p><b>Money</b> is positive signed-long minor units of one fixed {@link Currency}; the arithmetic is
 * checked in Java (never in Lua), refusing zero or negative amounts, insufficient available balance,
 * capacity overflow and {@code long} overflow before anything is written. {@code sharedCapacity 0}
 * means no policy cap, still bounded by {@link Long#MAX_VALUE}. Personal balances are keyed by
 * {@code (member, epoch)} so a rejoined member never inherits a removed epoch's money. Reserved
 * amounts belong to pending transfers; available is posted minus reserved. Build 51 stores and decodes
 * this record; bank operations are Build 54.</p>
 *
 * @param profileId the profile
 * @param revision {@code S}
 * @param currency the fixed currency identity
 * @param sharedBalance posted shared balance
 * @param sharedCapacity policy cap of the shared balance, {@code 0} = none
 * @param personalBalances posted personal balances by member key
 * @param collections collection id to amount
 * @param contributions collection id to (member key to contributed amount)
 * @param reserved transfer operation id to reserved amount (shared bank)
 * @param pendingIntent operation holding a reservation on this record, or {@code null}
 */
public record ProfileSharedState(UUID profileId, long revision, Currency currency, long sharedBalance, long sharedCapacity,
                                 Map<MemberKey, Long> personalBalances, Map<String, Long> collections,
                                 Map<String, Map<MemberKey, Long>> contributions, Map<UUID, Long> reserved,
                                 UUID pendingIntent) {

    public static final int MAX_COLLECTIONS = 256;
    public static final int MAX_COLLECTION_ID = 64;
    public static final int MAX_RESERVATIONS = 64;

    public ProfileSharedState {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(currency, "currency");
        CoopWriteGuard.counter(revision, "shared revision");
        if (sharedBalance < 0) throw new IllegalArgumentException("shared balance " + sharedBalance);
        if (sharedCapacity < 0) throw new IllegalArgumentException("shared capacity " + sharedCapacity);
        personalBalances = unmodifiable(personalBalances);
        if (personalBalances.size() > AuroraProfile.MAX_MEMBERS) throw new IllegalArgumentException("too many personal balances");
        for (final long balance : personalBalances.values()) {
            if (balance < 0) throw new IllegalArgumentException("personal balance " + balance);
        }
        collections = unmodifiable(collections);
        if (collections.size() > MAX_COLLECTIONS) throw new IllegalArgumentException("too many collections");
        for (final Map.Entry<String, Long> entry : collections.entrySet()) {
            if (!validCollection(entry.getKey())) throw new IllegalArgumentException("collection id " + entry.getKey());
            if (entry.getValue() < 0) throw new IllegalArgumentException("collection amount " + entry.getValue());
        }
        final Map<String, Map<MemberKey, Long>> ledger = new LinkedHashMap<>();
        (contributions == null ? Map.<String, Map<MemberKey, Long>>of() : contributions).forEach((collection, perMember) -> {
            if (!validCollection(collection)) throw new IllegalArgumentException("collection id " + collection);
            final Map<MemberKey, Long> copy = unmodifiable(perMember);
            if (copy.size() > AuroraProfile.MAX_MEMBERS) throw new IllegalArgumentException("too many contributors");
            for (final long amount : copy.values()) {
                if (amount < 0) throw new IllegalArgumentException("contribution " + amount);
            }
            ledger.put(collection, copy);
        });
        if (ledger.size() > MAX_COLLECTIONS) throw new IllegalArgumentException("too many contribution ledgers");
        contributions = Collections.unmodifiableMap(ledger);
        reserved = unmodifiable(reserved);
        if (reserved.size() > MAX_RESERVATIONS) throw new IllegalArgumentException("too many reservations");
        long totalReserved = 0L;
        for (final long amount : reserved.values()) {
            if (amount <= 0) throw new IllegalArgumentException("reserved amount " + amount);
            totalReserved = Math.addExact(totalReserved, amount);
        }
        if (totalReserved > sharedBalance) throw new IllegalArgumentException("reserved more than the shared balance");
    }

    /** A new profile's shared state: revision 0, zero balances. */
    public static ProfileSharedState empty(final UUID profileId, final Currency currency, final long sharedCapacity) {
        return new ProfileSharedState(profileId, 0L, currency, 0L, sharedCapacity, Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    private static boolean validCollection(final String id) {
        return id != null && id.matches("[a-z0-9_:.-]{1," + MAX_COLLECTION_ID + "}");
    }

    private static <K, V> Map<K, V> unmodifiable(final Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map == null ? Map.of() : map));
    }

    /** Shared balance minus pending reservations. */
    public long sharedAvailable() {
        long reservedTotal = 0L;
        for (final long amount : this.reserved.values()) reservedTotal += amount;
        return this.sharedBalance - reservedTotal;
    }

    public long personalBalance(final MemberKey member) {
        return this.personalBalances.getOrDefault(member, 0L);
    }

    // ------------------------------------------------------------------ checked mutations (after-images at S + 1)

    /** Deposit into the shared bank; refuses non-positive amounts, capacity and long overflow. */
    public ProfileSharedState depositShared(final long amount) {
        positive(amount);
        final long next = add(this.sharedBalance, amount);
        if (this.sharedCapacity > 0 && next > this.sharedCapacity) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the shared bank of " + this.profileId
                + " would exceed its capacity " + this.sharedCapacity + "; nothing was written");
        }
        return copy(next, this.personalBalances, this.collections, this.contributions, this.reserved);
    }

    /** Withdraw from the shared bank; refuses more than the available (posted minus reserved) balance. */
    public ProfileSharedState withdrawShared(final long amount) {
        positive(amount);
        if (amount > sharedAvailable()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the shared bank of " + this.profileId
                + " has " + sharedAvailable() + " available, not " + amount + "; nothing was written");
        }
        return copy(this.sharedBalance - amount, this.personalBalances, this.collections, this.contributions, this.reserved);
    }

    /** Deposit into a member's personal subaccount; {@code personalCapacity 0} means no cap. */
    public ProfileSharedState depositPersonal(final MemberKey member, final long amount, final long personalCapacity) {
        positive(amount);
        final long next = add(personalBalance(member), amount);
        if (personalCapacity > 0 && next > personalCapacity) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the personal bank of " + member
                + " would exceed its capacity " + personalCapacity + "; nothing was written");
        }
        final Map<MemberKey, Long> balances = new LinkedHashMap<>(this.personalBalances);
        balances.put(member, next);
        return copy(this.sharedBalance, balances, this.collections, this.contributions, this.reserved);
    }

    /** Withdraw from a member's personal subaccount. */
    public ProfileSharedState withdrawPersonal(final MemberKey member, final long amount) {
        positive(amount);
        final long current = personalBalance(member);
        if (amount > current) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the personal bank of " + member + " has "
                + current + ", not " + amount + "; nothing was written");
        }
        final Map<MemberKey, Long> balances = new LinkedHashMap<>(this.personalBalances);
        balances.put(member, current - amount);
        return copy(this.sharedBalance, balances, this.collections, this.contributions, this.reserved);
    }

    /** Reserve part of the shared balance for transfer {@code operationId}. */
    public ProfileSharedState reserve(final UUID operationId, final long amount) {
        positive(amount);
        if (this.reserved.containsKey(operationId)) throw new IllegalArgumentException("already reserved by " + operationId);
        if (amount > sharedAvailable()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "cannot reserve " + amount + " of "
                + sharedAvailable() + " available; nothing was written");
        }
        final Map<UUID, Long> next = new LinkedHashMap<>(this.reserved);
        next.put(operationId, amount);
        return copy(this.sharedBalance, this.personalBalances, this.collections, this.contributions, next);
    }

    /** Release a reservation without posting it (abort). */
    public ProfileSharedState releaseReservation(final UUID operationId) {
        if (!this.reserved.containsKey(operationId)) throw new IllegalArgumentException("no reservation " + operationId);
        final Map<UUID, Long> next = new LinkedHashMap<>(this.reserved);
        next.remove(operationId);
        return copy(this.sharedBalance, this.personalBalances, this.collections, this.contributions, next);
    }

    /** Post a reservation: the reserved amount leaves the shared balance (commit of an outgoing transfer). */
    public ProfileSharedState postReservation(final UUID operationId) {
        final Long amount = this.reserved.get(operationId);
        if (amount == null) throw new IllegalArgumentException("no reservation " + operationId);
        final Map<UUID, Long> next = new LinkedHashMap<>(this.reserved);
        next.remove(operationId);
        return copy(this.sharedBalance - amount, this.personalBalances, this.collections, this.contributions, next);
    }

    /** Add to a collection and credit the contributing member; checked, nonnegative. */
    public ProfileSharedState contribute(final String collection, final MemberKey member, final long amount) {
        positive(amount);
        if (!validCollection(collection)) throw new IllegalArgumentException("collection id " + collection);
        final Map<String, Long> totals = new LinkedHashMap<>(this.collections);
        totals.put(collection, add(totals.getOrDefault(collection, 0L), amount));
        final Map<String, Map<MemberKey, Long>> ledger = new LinkedHashMap<>(this.contributions);
        final Map<MemberKey, Long> perMember = new LinkedHashMap<>(ledger.getOrDefault(collection, Map.of()));
        perMember.put(member, add(perMember.getOrDefault(member, 0L), amount));
        ledger.put(collection, perMember);
        return copy(this.sharedBalance, this.personalBalances, totals, ledger, this.reserved);
    }

    /** Installs or clears ({@code null}) the reservation of an intent; {@code S} advances. */
    public ProfileSharedState withPendingIntent(final UUID operationId) {
        return new ProfileSharedState(this.profileId, CoopWriteGuard.next(this.revision, "shared state of " + this.profileId),
            this.currency, this.sharedBalance, this.sharedCapacity, this.personalBalances, this.collections,
            this.contributions, this.reserved, operationId);
    }

    /** The same record at an explicit revision (decoders and after-images only). */
    public ProfileSharedState atRevision(final long revision) {
        return new ProfileSharedState(this.profileId, revision, this.currency, this.sharedBalance, this.sharedCapacity,
            this.personalBalances, this.collections, this.contributions, this.reserved, this.pendingIntent);
    }

    private ProfileSharedState copy(final long sharedBalance, final Map<MemberKey, Long> personalBalances,
                                    final Map<String, Long> collections, final Map<String, Map<MemberKey, Long>> contributions,
                                    final Map<UUID, Long> reserved) {
        return new ProfileSharedState(this.profileId, CoopWriteGuard.next(this.revision, "shared state of " + this.profileId),
            this.currency, sharedBalance, this.sharedCapacity, personalBalances, collections, contributions, reserved,
            this.pendingIntent);
    }

    private static void positive(final long amount) {
        if (amount <= 0) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "amount must be positive, not " + amount);
        }
    }

    private static long add(final long current, final long amount) {
        try {
            return Math.addExact(current, amount);
        } catch (final ArithmeticException overflow) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "balance overflow; nothing was written");
        }
    }

    /**
     * The fixed currency identity of a profile's money. Changing provider, currency or scale needs a
     * separate offline migration, never an implicit reinterpretation.
     *
     * @param providerId the economy provider ({@code [a-z0-9_.-]{1,64}})
     * @param currencyId the currency within the provider
     * @param scale decimal places of the minor unit (0..9)
     */
    public record Currency(String providerId, String currencyId, int scale) {

        public Currency {
            if (providerId == null || !providerId.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("providerId");
            if (currencyId == null || !currencyId.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("currencyId");
            if (scale < 0 || scale > 9) throw new IllegalArgumentException("scale " + scale);
        }
    }

    /** A membership: member id and epoch. */
    public record MemberKey(UUID playerId, UUID epoch) {

        public MemberKey {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(epoch, "epoch");
        }

        public static MemberKey of(final ProfileMember member) {
            return new MemberKey(member.playerId(), member.epoch());
        }

        @Override
        public String toString() {
            return this.playerId + "/" + this.epoch;
        }
    }
}
