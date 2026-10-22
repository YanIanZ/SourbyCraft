package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.command.UpdateCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /update}'s off-thread result must come back through the reply hop (audit OBS-8). A
 * command block's {@code sendMessage} writes block-entity state and throws off its region thread;
 * before this the continuation called the sender directly from the worker.
 */
class UpdateCommandReplyTest {

    record Hop(CommandSender sender, Runnable reply) {}

    @Test
    void asyncResultReachesACommandBlockOnlyThroughTheHop(@TempDir Path dir) {
        BlockCommandSender block = mock(BlockCommandSender.class);
        when(block.hasPermission(anyString())).thenReturn(true);

        // A real updater over a scripted transport: the listing is empty, so the outcome is NO_RELEASE.
        HttpTransport transport = (url, headers) -> new HttpTransport.Response(200, Map.of(),
            new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)));
        SourbyUpdater updater = new SourbyUpdater(dir.resolve("auto_update"), transport,
            () -> new BuildInfo("26.2-REL", "47", "47", "26.2", "", "", ""), Clock.systemUTC(),
            ReleaseSignatureVerifier.inactive(), new UpdateRollbackGuard(dir.resolve("auto_update")));

        List<Hop> hops = new ArrayList<>();
        UpdateCommand cmd = new UpdateCommand("update", () -> updater, Runnable::run,
            (sender, reply) -> hops.add(new Hop(sender, reply)), new UpdateRollbackGuard(dir.resolve("auto_update")));

        cmd.execute(block, "update", new String[]{"check"});

        // The worker has finished (Runnable::run), yet the block has only seen the synchronous
        // "Checking..." line: the result is parked in the hop, not sent from the worker.
        verify(block, times(1)).sendMessage(any(Component.class));
        assertEquals(1, hops.size());
        assertSame(block, hops.get(0).sender());

        hops.get(0).reply().run();
        verify(block, times(2)).sendMessage(any(Component.class));
    }

    @Test
    void everyOutcomeHasAReplyLine() {
        for (SourbyUpdater.Outcome o : SourbyUpdater.Outcome.values()) {
            // describe() is an exhaustive switch; this guards the enum growing without a line.
            assertNotNull(UpdateCommand.describe(o), o.name());
        }
    }
}
