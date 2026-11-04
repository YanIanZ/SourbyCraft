package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;

/**
 * The permanent receipt of one bank, collection, transfer or salvage operation (added in Build 54), as
 * {@code AuroraProfiles.bankReceipt} answers it. A receipt exists once the store decided the operation; it carries no
 * balances (read {@code bank()} for those), only what was decided.
 *
 * @param operationId the operation
 * @param kind the server's operation kind, for logs ({@code SHARED_MUTATE}, {@code BANK_TRANSFER}, {@code SALVAGE_DELIVER}, ...)
 * @param committed {@code true} when the operation committed, {@code false} when it was aborted
 * @param decidedAtMillis when the store decided it, on the store clock
 */
public record ProfileReceiptInfo(UUID operationId, String kind, boolean committed, long decidedAtMillis) {

    public ProfileReceiptInfo {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
    }
}
