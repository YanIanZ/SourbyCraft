package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.iyanz.sourbycraft.command.SourbyReply.Hop;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

/**
 * Which thread a command reply has to run on.
 *
 * <p>"Non-player" was treated as one category with one safety story. It is not: a logger and a
 * block the world owns behave nothing alike.</p>
 */
public class SourbyReplyTest {

    @Test
    void aCommandBlockNeedsTheRegionThatOwnsIt() {
        // BaseCommandBlock.sendSystemMessage writes lastOutput on the block entity and calls
        // onUpdated, behind both AsyncCatcher and Folia's threadCheck. Replying to one from a
        // worker throws, and this class used to swallow that -- so the reply was simply lost.
        assertEquals(Hop.REGION, SourbyReply.hopFor(mock(BlockCommandSender.class)));
    }

    @Test
    void sendersThatOwnNoWorldStateNeedNoHop() {
        assertEquals(Hop.DIRECT, SourbyReply.hopFor(mock(ConsoleCommandSender.class)));
        assertEquals(Hop.DIRECT, SourbyReply.hopFor(mock(RemoteConsoleCommandSender.class)));
    }

    @Test
    void anyEntitySenderNeedsItsOwnScheduler() {
        // Not only players: /execute as <entity> run <command> gives a non-player entity sender,
        // and an entity is owned by whichever region holds it.
        assertEquals(Hop.ENTITY, SourbyReply.hopFor(mock(Player.class)));
        assertEquals(Hop.ENTITY, SourbyReply.hopFor(mock(Zombie.class)));
    }

    @Test
    void everySenderGetsADecisionRatherThanFallingThrough() {
        for (final Class<?> type : new Class<?>[] {
            Player.class, Zombie.class, BlockCommandSender.class,
            ConsoleCommandSender.class, RemoteConsoleCommandSender.class}) {
            assertNotNull(SourbyReply.hopFor((org.bukkit.command.CommandSender) mock(type)),
                type.getSimpleName() + " has no hop");
        }
    }
}
