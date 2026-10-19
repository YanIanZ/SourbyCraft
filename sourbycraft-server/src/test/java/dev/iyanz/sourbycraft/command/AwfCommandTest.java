package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.iyanz.sourbycraft.api.world.UnloadResult;
import dev.iyanz.sourbycraft.awf.world.AuroraWorldsService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The command's own rules, with the world service mocked: which files it will touch (OBS-11) and
 * which thread a completion reply takes (OBS-8).
 */
class AwfCommandTest {

    private static final Path EXPORTS = Path.of("awf", "exports");
    private static final Path IMPORTS = Path.of("awf", "imports");

    private final AuroraWorldsService worlds = mock(AuroraWorldsService.class);
    private final ReplyProbe hop = new ReplyProbe();
    private AwfCommand command;

    @BeforeEach
    void command() {
        this.command = new AwfCommand("awf", () -> this.worlds, this.hop);
        this.command.permissionMessage(Component.text("no permission"));   // keeps Bukkit.permissionMessage() out of a unit test
    }

    private List<String> texts() {
        return this.hop.received().stream().map(ReplyProbe.Received::text).toList();
    }

    @Test
    void exportWritesInsideAwfExportsWithTheSuffixApplied() {
        when(this.worlds.exportWorld(any(), any())).thenReturn(new CompletableFuture<>());
        final CommandSender console = this.hop.sender(ConsoleCommandSender.class, true);

        this.command.execute(console, "awf", new String[] {"export", "Hub", "backup"});

        verify(this.worlds).exportWorld("hub", EXPORTS.resolve("backup.awf"));
        assertEquals(List.of("Working..."), texts());
    }

    @Test
    void exportRefusesPathsThatLeaveAwfExports() {
        // Before: /awf export world /etc/cron.d/x wrote wherever the server process could; the
        // command permission is for moving worlds, not for writing files on the host.
        final CommandSender console = this.hop.sender(ConsoleCommandSender.class, true);
        for (final String escape : List.of("/etc/cron.d/x", "../x", "a/../../x", "awf/exports/../../x", "")) {
            this.command.execute(console, "awf", new String[] {"export", "hub", escape});
            final List<String> replies = texts();
            assertEquals(1, replies.size(), escape + " -> " + replies);
            assertTrue(replies.getFirst().contains("awf/exports"), escape + " -> " + replies.getFirst());
            assertFalse(replies.getFirst().contains("Working"), escape);
        }
        verify(this.worlds, never()).exportWorld(any(), any());
    }

    @Test
    void importReadsInsideAwfImportsAndRefusesEscapes() throws Exception {
        when(this.worlds.importWorld(any(), any(), anyBoolean())).thenReturn(new CompletableFuture<>());
        final CommandSender console = this.hop.sender(ConsoleCommandSender.class, true);

        this.command.execute(console, "awf", new String[] {"import", "../../server.properties", "hub"});
        assertTrue(texts().getFirst().contains("awf/imports"));
        this.command.execute(console, "awf", new String[] {"import", "/etc/passwd", "hub"});
        assertTrue(texts().getFirst().contains("awf/imports"));
        verify(this.worlds, never()).importSlime(any(), any(), any(), anyBoolean());
        verify(this.worlds, never()).importWorld(any(), any(), anyBoolean());

        // A name inside the folder is read from awf/imports/<name> (relative to the working
        // directory, as on a server). The file only has to start like an .awf file for the
        // command to route it; the mocked service never opens it. (The Slime route builds a
        // WorldCreator, which needs a live server, so it is covered by the convert test.)
        final String name = "cmdtest-" + java.util.UUID.randomUUID() + ".awf";
        final Path file = IMPORTS.resolve(name);
        java.nio.file.Files.createDirectories(IMPORTS);
        try {
            java.nio.file.Files.write(file, new byte[] {'A', 'W', 'F', 'W'});
            this.command.execute(console, "awf", new String[] {"import", name, "Hub", "autoload"});
            verify(this.worlds).importWorld(file, "hub", true);
            assertEquals(List.of("Working..."), texts());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            try (var left = java.nio.file.Files.list(IMPORTS)) {
                if (left.findAny().isEmpty()) {
                    java.nio.file.Files.delete(IMPORTS);
                    try (var parent = java.nio.file.Files.list(IMPORTS.getParent())) {
                        if (parent.findAny().isEmpty()) java.nio.file.Files.delete(IMPORTS.getParent());
                    }
                }
            }
        }
    }

    @Test
    void convertReadsFromImportsAndWritesToExports() {
        when(this.worlds.convertSlime(any(), any(), any())).thenReturn(new CompletableFuture<>());
        final CommandSender console = this.hop.sender(ConsoleCommandSender.class, true);

        this.command.execute(console, "awf", new String[] {"convert", "old.slime", "new", "nether"});
        verify(this.worlds).convertSlime(IMPORTS.resolve("old.slime"), EXPORTS.resolve("new.awf"), World.Environment.NETHER);

        this.command.execute(console, "awf", new String[] {"convert", "old.slime", "../new"});
        this.command.execute(console, "awf", new String[] {"convert", "../old.slime", "new"});
        verify(this.worlds, times(1)).convertSlime(any(), any(), any());
    }

    @Test
    void aCommandBlockGetsTheCompletionThroughItsRegionHop() throws Exception {
        // The operation completes on the storage lane. A command block's sendMessage writes
        // block-entity state behind Folia's thread check, so the report must take the hop.
        final CompletableFuture<World> loading = new CompletableFuture<>();
        when(this.worlds.load("hub")).thenReturn(loading);
        final BlockCommandSender block = this.hop.sender(BlockCommandSender.class, true);
        assertEquals(SourbyReply.Hop.REGION, SourbyReply.hopFor(block));

        this.command.execute(block, "awf", new String[] {"load", "hub"});
        final ReplyProbe.Received working = this.hop.next();
        assertEquals("Working...", working.text());
        assertFalse(working.throughHop(), "the acknowledgement is sent on the command thread itself");

        Thread.ofVirtual().start(() -> loading.complete(null)).join();
        final ReplyProbe.Received loaded = this.hop.await("Loaded hub");
        assertTrue(loaded.throughHop());

        final CompletableFuture<Void> saving = new CompletableFuture<>();
        when(this.worlds.save("hub")).thenReturn(saving);
        this.command.execute(block, "awf", new String[] {"save", "hub"});
        this.hop.next();
        Thread.ofVirtual().start(() -> saving.completeExceptionally(new IllegalStateException("held by another server"))).join();
        final ReplyProbe.Received failed = this.hop.await("Failed");
        assertEquals("Failed: held by another server", failed.text());
        assertTrue(failed.throughHop());
    }

    @Test
    void anUnloadResultTakesTheHopToo() throws Exception {
        when(this.worlds.unload("hub", true)).thenReturn(CompletableFuture.completedFuture(UnloadResult.SUCCESS));
        final BlockCommandSender block = this.hop.sender(BlockCommandSender.class, true);

        this.command.execute(block, "awf", new String[] {"unload", "hub"});
        final ReplyProbe.Received unloaded = this.hop.await("Unloaded hub");
        assertTrue(unloaded.throughHop());
        assertEquals(1, this.hop.hops());
    }
}
