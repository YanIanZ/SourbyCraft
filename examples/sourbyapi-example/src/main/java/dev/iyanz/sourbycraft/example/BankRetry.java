package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileBankInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The retry contract of the co-op bank (Build 54), as a consumer uses it. Not wired to a command: the server moves no
 * purse money, so a real caller debits its own economy first, and this example holds none.
 *
 * <p>The rules: the caller chooses the operation id (any UUID it never used before) and keeps it until the outcome is
 * known. A server that already decided that id answers from the permanent receipt without posting again. A failure
 * that carries {@code UNKNOWN_OUTCOME} must be re-read, not repeated and not refunded.</p>
 */
final class BankRetry {

    private BankRetry() {
    }

    /**
     * Deposits {@code amount} (minor units) already taken from the actor's purse.
     *
     * @return completes with {@code true} when the deposit is committed and {@code false} when it is certain that it is
     *     not (the caller may then refund the purse). A failed future means the outcome is still unknown: neither
     *     refund nor repeat with another id; call this again later with the same {@code operationId}.
     */
    static CompletableFuture<Boolean> depositOnce(final AuroraProfiles profiles, final UUID actor, final UUID profile,
                                                  final long amount, final UUID operationId) {
        return profiles.deposit(actor, profile, amount, operationId)
            .<Boolean>thenApply((ProfileBankInfo bank) -> true)
            .exceptionallyCompose(failure -> {
                final Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                if (!(cause instanceof ProfileOperationException op)) {
                    return CompletableFuture.failedFuture(cause); // not a refusal of the service: outcome unknown
                }
                if (op.code() == ProfileErrorCode.UNKNOWN_OUTCOME || op.code() == ProfileErrorCode.RECOVERY_REQUIRED) {
                    return CompletableFuture.failedFuture(op);     // undecided: retry the SAME id later
                }
                // Any other refusal may still follow a commit under this id (an earlier attempt whose answer was lost).
                // Before refunding, read the permanent receipt: refund only when it shows no COMMITTED operation.
                return profiles.bankReceipt(null, profile, operationId).thenApply(receipt -> receipt.map(r -> r.committed()).orElse(false));
            });
    }
}
