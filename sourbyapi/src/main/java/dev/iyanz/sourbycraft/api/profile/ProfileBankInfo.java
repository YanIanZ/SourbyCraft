package dev.iyanz.sourbycraft.api.profile;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. The bank of a co-op profile as one member (or an operator) sees it, read from one committed shared
 * record. All amounts are minor units of {@link #currency()}.
 *
 * @param profileId the co-op profile
 * @param revision the shared record's revision this view was read at ({@code 0} when no bank change was ever committed)
 * @param currency the currency the record is kept in
 * @param sharedBalance posted shared balance
 * @param sharedAvailable shared balance minus amounts reserved by pending transfers
 * @param sharedCapacity the record's shared cap, {@code 0} = none
 * @param personalBalance the viewer's personal bank in this co-op ({@code 0} for an operator view)
 * @param personalCapacity the personal cap derived from the policy percentage and {@code sharedCapacity}, {@code 0} = none
 * @param personalBalances the personal bank of every current member, by player id (current membership epoch only)
 * @param pendingTransfers transfers that hold a reservation on the shared record
 */
@NullMarked
public record ProfileBankInfo(UUID profileId, long revision, ProfileCurrency currency, long sharedBalance, long sharedAvailable,
                              long sharedCapacity, long personalBalance, long personalCapacity, Map<UUID, Long> personalBalances,
                              int pendingTransfers) {

    public ProfileBankInfo {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(currency, "currency");
        personalBalances = Map.copyOf(personalBalances);
    }
}
