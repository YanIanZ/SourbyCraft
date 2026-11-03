package dev.iyanz.sourbycraft.api.profile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. One collection of a co-op profile with its per-member contribution ledger. A collection is a
 * counter the consumer plugin defines (for example {@code wheat}); the server keeps the total and who contributed how
 * much, and moves no items or money for it.
 *
 * @param collection the collection id, {@code [a-z0-9_:.-]{1,64}}
 * @param total the collection's total
 * @param contributions what each player contributed, largest first; a player who contributed in several membership
 *     epochs is one entry with the sum
 */
@NullMarked
public record ProfileCollectionInfo(String collection, long total, List<Contribution> contributions) {

    public ProfileCollectionInfo {
        Objects.requireNonNull(collection, "collection");
        contributions = List.copyOf(contributions);
    }

    /**
     * One contributor's share of a collection.
     *
     * @param playerId the contributing player
     * @param amount the amount they contributed
     */
    public record Contribution(UUID playerId, long amount) {

        public Contribution {
            Objects.requireNonNull(playerId, "playerId");
        }
    }
}
