package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. The co-op bank policy this server runs ({@code aurora.awf.multiprofile.bank.*}, RESTART_REQUIRED),
 * readable without a co-op profile through {@link AuroraProfiles#bankPolicy()}: a consumer that formats amounts, sizes a
 * deposit menu or decides whether a purse currency matches the bank's reads it once at enable.
 *
 * @param currency the currency every bank record of this server is kept in; a record written in another currency is
 *     refused changes ({@link ProfileErrorCode#INVALID_REQUEST})
 * @param sharedCapacity the shared bank cap in minor units, {@code 0} = none
 * @param personalCapPercent the personal bank cap as a percentage of {@code sharedCapacity} ({@code 0}-{@code 100};
 *     a cap of 0 means none)
 * @param interestPercent the interest {@code applyInterest} credits, as a percentage of each balance
 * @param interestIntervalMillis the least interval between two interest postings, on the store clock
 */
@NullMarked
public record ProfileBankPolicy(ProfileCurrency currency, long sharedCapacity, int personalCapPercent, int interestPercent,
                                long interestIntervalMillis) {

    public ProfileBankPolicy {
        Objects.requireNonNull(currency, "currency");
        if (sharedCapacity < 0L) throw new IllegalArgumentException("sharedCapacity " + sharedCapacity);
        if (personalCapPercent < 0 || personalCapPercent > 100) throw new IllegalArgumentException("personalCapPercent " + personalCapPercent);
        if (interestPercent < 0 || interestPercent > 100) throw new IllegalArgumentException("interestPercent " + interestPercent);
        if (interestIntervalMillis < 0L) throw new IllegalArgumentException("interestIntervalMillis " + interestIntervalMillis);
    }
}
