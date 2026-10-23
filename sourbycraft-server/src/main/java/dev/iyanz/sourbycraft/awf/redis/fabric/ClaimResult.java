package dev.iyanz.sourbycraft.awf.redis.fabric;

/**
 * The outcome of a claim attempt.
 *
 * @param granted whether the caller holds the claim now
 * @param claim the claim, present when granted; when refused it is the holder that blocked it (with
 *     {@code fence = 0} when only an expired record was seen)
 * @param reason a short machine-readable reason: {@code granted}, {@code duplicate}, {@code held},
 *     {@code stale-fence}, {@code lost}
 */
public record ClaimResult(boolean granted, OwnerClaim claim, String reason) {

    public static ClaimResult granted(final OwnerClaim claim) {
        return new ClaimResult(true, claim, "granted");
    }

    public static ClaimResult refused(final String reason, final OwnerClaim holder) {
        return new ClaimResult(false, holder, reason);
    }
}
