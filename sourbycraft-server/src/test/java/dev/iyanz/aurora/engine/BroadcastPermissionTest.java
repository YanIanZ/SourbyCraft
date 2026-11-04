package dev.iyanz.aurora.engine;

import dev.iyanz.aurora.engine.util.LegacyNames;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code canvas.broadcasting.receiver} renamed to {@code aurora.broadcasting.receiver}: since Build 50 holders of only
 * the old node receive no engine broadcasts, with one line per player saying the old node is ignored.
 */
@ResourceLock("dev.iyanz.aurora.engine.util.LegacyNames")
class BroadcastPermissionTest {

    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void install() {
        LegacyNames.installForTesting(name -> null, this.warnings::add);
    }

    @AfterEach
    void restore() {
        LegacyNames.resetForTesting();
    }

    private static Player player(final String name, final boolean newNode, final boolean oldSet, final boolean oldValue) {
        final Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.hasPermission("aurora.broadcasting.receiver")).thenReturn(newNode);
        when(player.isPermissionSet("canvas.broadcasting.receiver")).thenReturn(oldSet);
        when(player.hasPermission("canvas.broadcasting.receiver")).thenReturn(oldValue);
        return player;
    }

    @Test
    void theNewNodeReceivesSilently() {
        assertEquals("aurora.broadcasting.receiver", BroadcastPermission.NODE);
        assertTrue(BroadcastPermission.receives(player("op", true, false, true)));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void anExplicitGrantOfTheOldNodeNoLongerReceivesAndWarnsOncePerPlayer() {
        final Player alice = player("alice", false, true, true);
        final Player bob = player("bob", false, true, true);

        assertFalse(BroadcastPermission.receives(alice));
        assertFalse(BroadcastPermission.receives(alice));
        assertFalse(BroadcastPermission.receives(bob));

        assertEquals(2, this.warnings.size(), this.warnings.toString());
        assertTrue(this.warnings.getFirst().contains("canvas.broadcasting.receiver"));
        assertTrue(this.warnings.getFirst().contains("aurora.broadcasting.receiver"));
        assertTrue(this.warnings.getFirst().contains("alice"));
        assertTrue(this.warnings.getFirst().contains("ignored since Build 50"));
    }

    @Test
    void anOldNodeThatIsOnlyTheOperatorDefaultIsNotADeprecatedGrant() {
        // not set explicitly: hasPermission(old) is just the op default, which the new node already covers
        assertFalse(BroadcastPermission.receives(player("deop", false, false, true)));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void anExplicitDenialOfTheOldNodeReceivesNothing() {
        assertFalse(BroadcastPermission.receives(player("denied", false, true, false)));
        assertEquals(List.of(), this.warnings);
    }
}
