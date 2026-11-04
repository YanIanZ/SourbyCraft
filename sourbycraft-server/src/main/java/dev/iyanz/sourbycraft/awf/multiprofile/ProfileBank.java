package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.Currency;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSharedState.MemberKey;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The co-op bank of a profile (Build 54, COOP-DESIGN §8, slice 6): the shared bank, the members' personal subaccounts,
 * collections with their per-member contribution ledger, and the shared↔personal transfer.
 *
 * <p>Everything here is decided in Java from the exact {@link ProfileSharedState} the engine read; nothing is applied.
 * The repository compares {@code S} (and {@code A}, {@code M}, the actor's active profile) in the same atomic step as it
 * installs the after-image, so a concurrent mutation makes the commit {@code STALE_STATE} and the caller re-reads. Money
 * is signed-long minor units of the profile's fixed {@link Currency}; the arithmetic is checked
 * ({@link ProfileSharedState}), and every refusal happens before anything is written.</p>
 *
 * <p><b>What is not here (owner decision OD-4):</b> the purse. The purse is per profile through a REQUIRED state
 * adapter; a transfer between the purse and the bank is refused ({@link #requireBankAccount}) until a transactional
 * purse provider exists. A deposit or withdrawal is a <em>ledger</em> credit or debit by an authorised member; the
 * consumer plugin that holds the purse moves the money on its side (its own receipts). The engine mints nothing on a
 * player command: {@code /coop bank} offers balances and the internal transfer only.</p>
 *
 * <p><b>Policy</b> ({@link Policy}): the shared capacity ({@code 0} = none), the personal bank cap as a percentage of
 * the shared capacity (Hypixel parity 20 %), the interest percentage (parity 10 %) and the currency identity. The
 * values are policy, not hard-coded; they are RESTART_REQUIRED (the engine reads them once).</p>
 */
public final class ProfileBank {

    private ProfileBank() {}

    /** The bank accounts a transfer may name; {@link #PURSE} is refused (OD-4). */
    public enum Account {
        SHARED,
        PERSONAL,
        PURSE;

        public static Account parse(final String text) {
            if (text == null) throw new IllegalArgumentException("account");
            return switch (text.toLowerCase(java.util.Locale.ROOT)) {
                case "shared", "coop", "co-op", "bank" -> SHARED;
                case "personal", "own", "mine" -> PERSONAL;
                case "purse", "wallet" -> PURSE;
                default -> throw new IllegalArgumentException("an account is shared, personal or purse, not " + text);
            };
        }
    }

    /**
     * Bank policy values.
     *
     * @param currency the fixed currency identity new shared records are created with
     * @param sharedCapacity the shared bank cap in minor units, {@code 0} = no policy cap (still {@link Long#MAX_VALUE})
     * @param personalCapPercent personal subaccount cap as a percentage of {@code sharedCapacity} (0 = no cap)
     * @param interestPercent interest credited by {@code applyInterest} (0 = none)
     * @param interestIntervalMillis the least time between two interest postings of one profile, judged on the store clock
     *     (review P3-22; {@code bank.interest-interval-hours}); {@code 0} = no minimum
     */
    public record Policy(Currency currency, long sharedCapacity, int personalCapPercent, int interestPercent, long interestIntervalMillis) {

        public static final int MAX_PERCENT = 100;
        /** The default least interval between two interest postings: 24 hours. */
        public static final long DEFAULT_INTEREST_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L;
        /** Hypixel parity: personal bank 20 % of the shared cap, 10 % interest at most once a day; no shared cap unless configured. */
        public static final Policy DEFAULT = new Policy(new Currency("aurora", "coins", 0), 0L, 20, 10, DEFAULT_INTEREST_INTERVAL_MILLIS);

        /** A policy with the default interest interval. */
        public Policy(final Currency currency, final long sharedCapacity, final int personalCapPercent, final int interestPercent) {
            this(currency, sharedCapacity, personalCapPercent, interestPercent, DEFAULT_INTEREST_INTERVAL_MILLIS);
        }

        public Policy {
            Objects.requireNonNull(currency, "currency");
            if (sharedCapacity < 0) throw new IllegalArgumentException("shared capacity " + sharedCapacity);
            if (personalCapPercent < 0 || personalCapPercent > MAX_PERCENT) throw new IllegalArgumentException("personal cap percent");
            if (interestPercent < 0 || interestPercent > MAX_PERCENT) throw new IllegalArgumentException("interest percent");
            if (interestIntervalMillis < 0) throw new IllegalArgumentException("interest interval " + interestIntervalMillis);
        }

        /** The personal cap derived from the shared capacity: {@code 0} (none) when either value is 0. */
        public long personalCapacity(final ProfileSharedState shared) {
            final long base = shared.sharedCapacity();
            if (base <= 0 || this.personalCapPercent == 0) return 0L;
            return BigInteger.valueOf(base).multiply(BigInteger.valueOf(this.personalCapPercent)).divide(BigInteger.valueOf(MAX_PERCENT)).longValueExact();
        }

        /** A new profile's shared record under this policy. */
        public ProfileSharedState empty(final UUID profileId) {
            return ProfileSharedState.empty(profileId, this.currency, this.sharedCapacity);
        }
    }

    // ------------------------------------------------------------------ views

    /** A member's view of the bank (server-internal; the API DTO is the integrator's). */
    public record View(UUID profileId, long revision, Currency currency, long sharedBalance, long sharedAvailable, long sharedCapacity,
                       long personalBalance, long personalCapacity, Map<UUID, Long> personalBalances, int pendingTransfers) {

        public View {
            personalBalances = Map.copyOf(personalBalances);
        }
    }

    /** The collections of a profile with the contribution ledger (server-internal). */
    public record Collections(UUID profileId, long revision, Map<String, Long> totals, Map<String, Map<UUID, Long>> contributions) {

        public Collections {
            totals = Map.copyOf(totals);
            final Map<String, Map<UUID, Long>> copy = new LinkedHashMap<>();
            contributions.forEach((collection, perMember) -> copy.put(collection, Map.copyOf(perMember)));
            contributions = java.util.Collections.unmodifiableMap(copy);
        }
    }

    /** The kinds of ledger posting a committed bank operation made. */
    public enum Entry {
        /** Shared bank credit ({@code bank.deposit}). */
        DEPOSIT,
        /** Shared bank debit ({@code bank.withdraw}). */
        WITHDRAW,
        /** Personal subaccount credit ({@code bank.personal}). */
        DEPOSIT_PERSONAL,
        /** Personal subaccount debit ({@code bank.personal}). */
        WITHDRAW_PERSONAL,
        /** Collection credit with the contributor's ledger entry ({@code collections.contribute}). */
        CONTRIBUTE,
        /** Interest credited to the shared balance and the personal subaccounts. */
        INTEREST,
        /** Shared&lt;-&gt;personal transfer ({@code bank.transfer}), one intent. */
        TRANSFER
    }

    /**
     * What a committed ledger operation did, for the engine's event hook (fired after the durable commit).
     *
     * @param kind {@link CoopOperation.Kind#SHARED_MUTATE} (direct guarded commit) or {@link CoopOperation.Kind#BANK_TRANSFER} (intent)
     * @param collection the collection of a {@link Entry#CONTRIBUTE}, else {@code null}
     * @param from the debited account of a {@link Entry#TRANSFER}, else {@code null}
     * @param to the credited account of a {@link Entry#TRANSFER}, else {@code null}
     * @param amount minor units posted; for {@link Entry#INTEREST} the total credited (shared and personal together)
     * @param sharedBalanceAfter the committed shared balance
     */
    public record LedgerEvent(UUID profileId, UUID actor, CoopOperation.Kind kind, Entry entry, String collection, Account from, Account to,
                              long amount, long sharedBalanceAfter, Currency currency, UUID operationId) {

        public LedgerEvent {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(operationId, "operationId");
        }
    }

    /** Minor units an after-image credited in total (shared balance and personal subaccounts), for an interest posting. */
    public static long credited(final ProfileSharedState before, final ProfileSharedState after) {
        long total = Math.subtractExact(after.sharedBalance(), before.sharedBalance());
        for (final Map.Entry<MemberKey, Long> entry : after.personalBalances().entrySet()) {
            total = Math.addExact(total, Math.subtractExact(entry.getValue(), before.personalBalance(entry.getKey())));
        }
        return total;
    }

    /**
     * A bank record keeps the currency it was first written with; amounts are minor units of that currency. When the
     * configured currency differs (the operator changed {@code bank.currency.*} after the record existed), a mutation is
     * refused rather than read a caller's minor units at the wrong scale or identity. Reads still show the stored
     * currency. Nothing was written.
     */
    public static void requireCurrency(final ProfileSharedState shared, final Policy policy) {
        if (!shared.currency().equals(policy.currency())) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the bank of " + shared.profileId() + " is kept in "
                + describe(shared.currency()) + " but aurora.awf.multiprofile.bank.currency is " + describe(policy.currency())
                + "; amounts would be read at the wrong scale or currency, so bank changes are refused until the configuration names"
                + " the stored currency again. Nothing was written");
        }
    }

    private static String describe(final Currency currency) {
        return currency.providerId() + ":" + currency.currencyId() + " (scale " + currency.scale() + ")";
    }

    public static View view(final ProfileSharedState shared, final ProfileMember viewer, final Policy policy, final AuroraProfile profile) {
        final Map<UUID, Long> personal = new LinkedHashMap<>();
        for (final ProfileMember member : profile.members()) {
            personal.put(member.playerId(), shared.personalBalance(MemberKey.of(member)));
        }
        final long own = viewer == null ? 0L : shared.personalBalance(MemberKey.of(viewer));
        return new View(shared.profileId(), shared.revision(), shared.currency(), shared.sharedBalance(), shared.sharedAvailable(),
            shared.sharedCapacity(), own, policy.personalCapacity(shared), personal, shared.reserved().size());
    }

    public static Collections collections(final ProfileSharedState shared) {
        final Map<String, Map<UUID, Long>> ledger = new LinkedHashMap<>();
        shared.contributions().forEach((collection, perMember) -> {
            final Map<UUID, Long> byPlayer = new LinkedHashMap<>();
            perMember.forEach((key, amount) -> byPlayer.merge(key.playerId(), amount, Long::sum));
            ledger.put(collection, byPlayer);
        });
        return new Collections(shared.profileId(), shared.revision(), shared.collections(), ledger);
    }

    // ------------------------------------------------------------------ checked decisions

    /** OD-4: only the shared and personal accounts take part in an engine transfer. */
    public static void requireBankAccount(final Account account) {
        if (account == Account.PURSE) {
            throw new ProfileOperationException(ProfileErrorCode.PURSE_TRANSFER_UNSUPPORTED, "owner decision OD-4: the purse stays per profile through"
                + " its REQUIRED state adapter and the engine has no transactional purse provider; a purse<->bank transfer is refused."
                + " The consumer plugin moves purse money on its side and credits or debits the bank ledger through the API."
                + " Nothing was written");
        }
    }

    /** Whether the role may take money out of the shared bank (OWNER or MANAGER; §8). */
    public static boolean mayWithdrawShared(final ProfileRole role) {
        return role == ProfileRole.OWNER || role == ProfileRole.MANAGER;
    }

    /**
     * The after-image of a shared↔personal transfer: both postings in one record, so one guarded commit is the whole
     * movement. Refuses a zero or negative amount, insufficient available funds and a cap or {@code long} overflow.
     */
    public static ProfileSharedState transferred(final ProfileSharedState shared, final MemberKey member, final Account from, final Account to,
                                                 final long amount, final Policy policy) {
        requireBankAccount(from);
        requireBankAccount(to);
        if (from == to) throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "a transfer moves money between the shared"
            + " and the personal bank; from and to are both " + from + ". Nothing was written");
        if (from == Account.PERSONAL) {
            // personal -> shared: debit the subaccount, credit the shared balance (cap checked).
            final ProfileSharedState debited = shared.withdrawPersonal(member, amount);
            return debited.depositShared(amount).atRevision(shared.revision() + 1L);
        }
        // shared -> personal: debit available shared funds, credit the subaccount under the personal cap.
        final ProfileSharedState debited = shared.withdrawShared(amount);
        return debited.depositPersonal(member, amount, policy.personalCapacity(shared)).atRevision(shared.revision() + 1L);
    }

    /**
     * Interest (policy percentage) credited to the shared balance and every personal subaccount, each capped at its
     * capacity; checked arithmetic, never a wrap. {@code null} when the percentage is 0 or nothing changes. Every personal
     * subaccount of the record is credited; see the overload for the engine's rules.
     */
    public static ProfileSharedState withInterest(final ProfileSharedState shared, final Policy policy) {
        return withInterest(shared, policy, null, true);
    }

    /**
     * {@link #withInterest(ProfileSharedState, Policy)} under the engine's rules (review P2-2, P2-4): personal
     * subaccounts are credited only when {@code includePersonal} (the type allows {@code bank.personal}) and only for the
     * memberships in {@code current} ({@code null} = every key; a removed membership's frozen money earns nothing). The
     * archived totals never earn interest.
     */
    public static ProfileSharedState withInterest(final ProfileSharedState shared, final Policy policy,
                                                  final java.util.Set<MemberKey> current, final boolean includePersonal) {
        if (policy.interestPercent() == 0) return null;
        ProfileSharedState next = shared;
        final long sharedInterest = capped(interestOf(shared.sharedBalance(), policy.interestPercent()), shared.sharedBalance(), shared.sharedCapacity());
        if (sharedInterest > 0) next = next.depositShared(sharedInterest).atRevision(shared.revision());
        if (includePersonal) {
            final long personalCap = policy.personalCapacity(shared);
            for (final Map.Entry<MemberKey, Long> entry : shared.personalBalances().entrySet()) {
                if (current != null && !current.contains(entry.getKey())) continue;
                final long interest = capped(interestOf(entry.getValue(), policy.interestPercent()), entry.getValue(), personalCap);
                if (interest > 0) next = next.depositPersonal(entry.getKey(), interest, personalCap).atRevision(shared.revision());
            }
        }
        if (next == shared) return null;
        return next.atRevision(shared.revision() + 1L);
    }

    private static long interestOf(final long balance, final int percent) {
        if (balance <= 0) return 0L;
        return BigInteger.valueOf(balance).multiply(BigInteger.valueOf(percent)).divide(BigInteger.valueOf(Policy.MAX_PERCENT)).longValueExact();
    }

    /** The part of {@code interest} that fits under {@code capacity} (0 = none) and under {@link Long#MAX_VALUE}. */
    private static long capped(final long interest, final long balance, final long capacity) {
        if (interest <= 0) return 0L;
        final long headroom = (capacity > 0 ? capacity : Long.MAX_VALUE) - balance;
        return Math.max(0L, Math.min(interest, headroom));
    }

    /**
     * Exact decimal-to-minor conversion for commands and the API: {@code "12.5"} at scale 2 is 1250; more fraction digits
     * than the scale, a non-positive value or one beyond {@code long} is {@code INVALID_REQUEST}.
     */
    public static long parseAmount(final String text, final Currency currency) {
        if (text == null || text.isBlank()) throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "an amount is required");
        final BigDecimal value;
        try {
            value = new BigDecimal(text.strip());
        } catch (final NumberFormatException invalid) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "'" + text + "' is not an amount");
        }
        if (value.signum() <= 0) throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the amount must be positive, not " + text);
        final BigDecimal minor = value.movePointRight(currency.scale());
        if (minor.stripTrailingZeros().scale() > 0) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "'" + text + "' has more than " + currency.scale()
                + " decimal place(s); the currency " + currency.currencyId() + " is counted in units of 10^-" + currency.scale());
        }
        try {
            return minor.toBigIntegerExact().longValueExact();
        } catch (final ArithmeticException overflow) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "'" + text + "' is beyond the largest amount");
        }
    }

    /** Minor units rendered with the currency's scale ({@code 1250} at scale 2 is {@code "12.50"}). */
    public static String format(final long minor, final Currency currency) {
        return BigDecimal.valueOf(minor).movePointLeft(currency.scale()).toPlainString();
    }

    // ------------------------------------------------------------------ the transfer intent

    /**
     * The transfer as a recoverable intent (COOP-DESIGN §5, §8): the shared record {@code S} is reserved (PREPARE
     * advances it once), the after-image at {@code S + 2} carries both postings, the actor's account is compared and the
     * profile's metadata reserved (released by the commit). A record never written is {@link CoopWriteGuard#ABSENT} before and after with the
     * after-image at 0. The receipt the commit leaves is permanent ({@link CoopOperation.Kind#permanent}); a replay of
     * the operation id returns it without posting again.
     */
    public static CoopIntentPlan transferPlan(final CoopOperation op, final AuroraProfile profile, final PlayerAccount actorAccount,
                                              final ProfileSharedState stored, final boolean storedExists, final ProfileSharedState after,
                                              final long deadlineMillis) throws IOException {
        Objects.requireNonNull(op, "op");
        if (op.kind() != CoopOperation.Kind.BANK_TRANSFER) throw new IllegalArgumentException("a transfer plan is a BANK_TRANSFER");
        if (!after.profileId().equals(profile.id()) || !stored.profileId().equals(profile.id())) {
            throw new IllegalArgumentException("the shared record is not the profile's");
        }
        if (after.pendingIntent() != null) throw new IllegalArgumentException("an after-image carries no reservation");
        final List<Expected> before = new ArrayList<>(3);
        final List<Expected> afterVector = new ArrayList<>(3);
        before.add(Expected.account(op.actor(), actorAccount.revision()));
        afterVector.add(Expected.account(op.actor(), actorAccount.revision()));
        // M is reserved as every co-op plan does (the repositories accept a plan with a reservation and release it at commit);
        // this serialises the transfer against membership changes of the same profile.
        before.add(Expected.metadata(profile.id(), profile.revision()));
        afterVector.add(Expected.metadata(profile.id(), profile.revision() + 1L));
        final long imageRevision;
        if (storedExists) {
            before.add(Expected.shared(profile.id(), stored.revision()));
            afterVector.add(Expected.shared(profile.id(), stored.revision() + 1L));
            imageRevision = stored.revision() + 2L;
        } else {
            before.add(Expected.shared(profile.id(), CoopWriteGuard.ABSENT));
            afterVector.add(Expected.shared(profile.id(), CoopWriteGuard.ABSENT));
            imageRevision = 0L;
        }
        final ProfileSharedState image = after.atRevision(imageRevision);
        final List<CoopIntentPlan.Image> images = List.of(new CoopIntentPlan.Image(ProfileRecords.TYPE_SHARED, profile.id(), null, null,
            imageRevision, ProfileRecords.sharedPayload2(image)));
        return new CoopIntentPlan(op, profile.id(), null, before, afterVector, images, List.of(), deadlineMillis, CoopIntentPlan.Result.NONE);
    }
}
