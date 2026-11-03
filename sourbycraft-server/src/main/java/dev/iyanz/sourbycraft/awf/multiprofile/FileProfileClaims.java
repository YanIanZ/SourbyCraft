package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Commit-time validation of the co-op claims a format-2 {@link FileProfileRepository} write carries.
 *
 * <p>The FILE repository calls these under its commit lock immediately before the write that is the
 * logical commit, after every revision comparison. The answers come from the engine's ownership providers
 * ({@link ProfileOwnership#validateTransaction}, {@link PlayerSessionOwnership#validate}), which compare
 * the exact {@code node/incarnation/owner} session and fence and check expiry: the process-local tables
 * with {@link System#nanoTime}, the Redis providers (allowed with the FILE repository in
 * {@code mode: standalone}, where the directory lock still keeps the store to one server) by Redis time
 * in a Lua script. The providers have their own lock, not the repository's: a lease can lapse while the
 * publication runs. The commit is then ordered at the validation, and every other FILE writer, including
 * the one that takes the lapsed lease over, serialises on the same repository lock and compares revisions
 * after this commit, so it is refused as stale rather than overwriting.</p>
 *
 * <p>With Redis providers each validation is a blocking Redis round trip under the FILE commit lock; the
 * repository is used from I/O workers only, never from a region, entity, global-tick or Netty thread.</p>
 */
public interface FileProfileClaims {

    /** Whether this exact {@code P} is live now. */
    boolean profileClaimLive(ProfileClaim claim) throws IOException;

    /** Whether this exact {@code Q} is live now. */
    boolean playerClaimLive(PlayerClaim claim) throws IOException;

    /**
     * The validator over the providers the engine uses. Both providers must agree on locality: the runtime
     * configures them together ({@code coordination.provider}).
     *
     * @throws IllegalArgumentException when one provider is distributed and the other is not
     */
    static FileProfileClaims of(final ProfileOwnership profiles, final PlayerSessionOwnership players) {
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(players, "players");
        if (profiles.distributed() != players.distributed()) {
            throw new IllegalArgumentException("the profile and player-session ownership providers must both be local or both"
                + " be distributed; a mixed pair cannot validate the claims of one write consistently");
        }
        return new FileProfileClaims() {
            @Override
            public boolean profileClaimLive(final ProfileClaim claim) throws IOException {
                try {
                    return Boolean.TRUE.equals(profiles.validateTransaction(claim).join());
                } catch (final CompletionException failed) {
                    throw new IOException("validating the profile claim on " + claim.profileId() + " failed", failed.getCause());
                }
            }

            @Override
            public boolean playerClaimLive(final PlayerClaim claim) throws IOException {
                try {
                    return Boolean.TRUE.equals(players.validate(claim).join());
                } catch (final CompletionException failed) {
                    throw new IOException("validating the player claim on " + claim.playerId() + " failed", failed.getCause());
                }
            }
        };
    }
}
