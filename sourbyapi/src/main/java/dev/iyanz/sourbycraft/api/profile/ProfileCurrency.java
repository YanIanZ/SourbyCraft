package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. The currency a co-op bank record is kept in. Amounts in the bank API are {@code long} minor units of
 * this currency: with {@code scale} 2, the amount {@code 1250} is 12.50.
 *
 * <p>A bank record keeps the currency it was first written with. The server refuses changes to a bank whose stored
 * currency differs from the configured {@code aurora.awf.multiprofile.bank.currency.*}
 * ({@link ProfileErrorCode#INVALID_REQUEST}), so a caller's minor units are never read at another scale.</p>
 *
 * @param providerId provider id, {@code [a-z0-9_.-]{1,64}}
 * @param currencyId currency id, {@code [a-z0-9_.-]{1,64}}
 * @param scale decimal places of one minor unit, 0-9
 */
@NullMarked
public record ProfileCurrency(String providerId, String currencyId, int scale) {

    public ProfileCurrency {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(currencyId, "currencyId");
        if (scale < 0 || scale > 9) throw new IllegalArgumentException("scale " + scale);
    }
}
