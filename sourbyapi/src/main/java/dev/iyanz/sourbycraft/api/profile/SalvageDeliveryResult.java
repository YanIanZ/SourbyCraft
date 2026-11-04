package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. The committed outcome of a {@linkplain AuroraProfiles#claimSalvage salvage claim}.
 *
 * @param salvageId the salvage record
 * @param profileId the co-op profile
 * @param claimantId the member the items were delivered to
 * @param deliveredStacks item stacks placed into the claimant's committed state by this claim
 * @param remainingStacks item stacks that did not fit and stay in the record for a later claim
 * @param complete whether the record is now delivered completely (nothing remains)
 * @param operationId the delivery operation, named by its permanent receipt
 * @param applied whether the committed state also reached the online player; {@code false} means the delivery is
 *     committed, the player's saves were refused, and their next authoritative login restores the committed state with
 *     the items. Nothing is re-offered either way.
 */
@NullMarked
public record SalvageDeliveryResult(UUID salvageId, UUID profileId, UUID claimantId, int deliveredStacks, int remainingStacks,
                                    boolean complete, UUID operationId, boolean applied) {

    public SalvageDeliveryResult {
        Objects.requireNonNull(salvageId, "salvageId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(claimantId, "claimantId");
        Objects.requireNonNull(operationId, "operationId");
    }
}
