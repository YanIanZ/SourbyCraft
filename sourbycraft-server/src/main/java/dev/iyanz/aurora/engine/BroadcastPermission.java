package dev.iyanz.aurora.engine;

import dev.iyanz.aurora.engine.util.LegacyNames;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.jspecify.annotations.NullMarked;

/**
 * Who receives engine configuration broadcasts ({@link GlobalConfiguration#broadcast}).
 *
 * <p>The node is {@value #NODE}. Its Canvas-era name {@value #LEGACY_NODE} still works when a permission plugin set
 * it explicitly, with a deprecation warning logged once per player, until {@link LegacyNames#REMOVAL_BUILD}. Neither
 * node is registered, so both default to operators; the old node is therefore only honoured when it is
 * {@linkplain Permissible#isPermissionSet set}, otherwise every operator would trigger the warning.</p>
 */
@NullMarked
public final class BroadcastPermission {

    public static final String NODE = "aurora.broadcasting.receiver";
    public static final String LEGACY_NODE = "canvas.broadcasting.receiver";

    private BroadcastPermission() {
    }

    public static boolean receives(final Permissible permissible) {
        if (permissible.hasPermission(NODE)) {
            return true;
        }
        if (permissible.isPermissionSet(LEGACY_NODE) && permissible.hasPermission(LEGACY_NODE)) {
            final String who = permissible instanceof Player player ? player.getName() : String.valueOf(permissible);
            LegacyNames.warnOnce("permission:" + LEGACY_NODE + ":" + who, "Permission " + LEGACY_NODE + " (held by " + who
                + ") is deprecated and stops working in " + LegacyNames.REMOVAL_BUILD + "; grant " + NODE + " instead.");
            return true;
        }
        return false;
    }
}
