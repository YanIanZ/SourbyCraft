package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.UUID;

/**
 * A proposed hook for refusing a profile switch, for example during combat or a dungeon.
 * Not wired yet on 26.2: no engine invokes this hook. Intended callers would use the profile I/O
 * lane rather than a region thread; implementations should be thread-safe and non-blocking.
 */
@FunctionalInterface
public interface SwitchGuard {

    /**
     * @return {@code null} to allow the switch, or a short player-safe reason to refuse it
     */
    String refusal(UUID playerId, UUID targetProfileId);
}
