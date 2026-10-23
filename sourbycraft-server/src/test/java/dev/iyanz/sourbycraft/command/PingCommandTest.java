package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.CommandTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.bukkit.Server;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /ping}: the cross-player permission is checked before any lookup, any sender gets a
 * reply, the latency gauge survives out-of-range pings, and the asynchronous geo reply always
 * takes the sender's hop (OBS-8) and never leaves the panel waiting on "looking up...".
 */
class PingCommandTest {

    private static final Executor INLINE = Runnable::run;

    private static Player player(final ReplyProbe probe, final String name, final int ping, final String brand) {
        final Player player = probe.sender(Player.class, true);
        when(player.getName()).thenReturn(name);
        when(player.getPing()).thenReturn(ping);
        when(player.getClientBrandName()).thenReturn(brand);
        when(player.getProtocolVersion()).thenReturn(770);
        return player;
    }

    private static Server serverWith(final Player... online) {
        final Server server = mock(Server.class);
        for (final Player p : online) when(server.getPlayer(p.getName())).thenReturn(p);
        return server;
    }

    private static PingCommand command(final ReplyProbe hop, final Function<Player, String> geo) {
        return quietDenial(new PingCommand("ping", INLINE, geo, hop));
    }

    @Test
    void pingingSomeoneElseWithoutThePermissionIsRefusedBeforeAnyLookup() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final Player alice = player(probe, "Alice", 30, null);
        final Player bob = player(probe, "Bob", 30, null);
        final Server server = serverWith(alice, bob);
        final AtomicInteger geoCalls = new AtomicInteger();
        final AtomicInteger executed = new AtomicInteger();
        final PingCommand command = new PingCommand("ping", task -> executed.incrementAndGet(),
            p -> { geoCalls.incrementAndGet(); return "x"; }, probe);
        final CommandSender sender = probe.sender(ConsoleCommandSender.class, false);
        when(sender.getName()).thenReturn("CONSOLE");

        withServer(server, () -> assertTrue(command.execute(sender, "ping", new String[] {"Bob"})));

        assertEquals("No permission", probe.next().text());
        assertTrue(probe.received().isEmpty());
        verifyNoInteractions(server);
        assertEquals(0, executed.get());
        assertEquals(0, geoCalls.get());
        assertEquals(0, probe.hops());
    }

    @Test
    void aPlayerPingingTheirOwnNameNeedsNoOtherPermission() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final Player alice = probe.sender(Player.class, false);       // no permissions at all
        when(alice.getName()).thenReturn("Alice");
        when(alice.getPing()).thenReturn(42);
        when(alice.getProtocolVersion()).thenReturn(770);

        final Server server = serverWith(alice);
        when(server.getPlayer("aLiCe")).thenReturn(alice);       // Bukkit's lookup is case-insensitive

        withServer(server, () ->
            assertTrue(command(probe, p -> null).execute(alice, "ping", new String[] {"aLiCe"})));

        final String all = plainReceived(probe.received());
        assertTrue(all.contains("Connection / Alice"), all);
        assertTrue(all.contains("42ms"), all);
        assertFalse(all.contains("No permission"), all);
    }

    @Test
    void withoutArgumentsANonPlayerGetsAReplyNotAnException() throws Exception {
        for (final Class<? extends CommandSender> type : List.of(ConsoleCommandSender.class, BlockCommandSender.class)) {
            final ReplyProbe probe = new ReplyProbe();
            final CommandSender sender = probe.sender(type, true);
            withServer(mock(Server.class), () ->
                assertDoesNotThrow(() -> command(probe, p -> null).execute(sender, "ping", new String[0])));
            assertEquals("Player not found", probe.next().text(), type.getSimpleName());
            assertTrue(probe.received().isEmpty());
        }
    }

    @Test
    void anUnknownTargetIsReportedForAPermittedSender() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final CommandSender console = probe.sender(ConsoleCommandSender.class, true);
        withServer(mock(Server.class), () ->
            assertTrue(command(probe, p -> null).execute(console, "ping", new String[] {"Nobody"})));
        assertEquals("Player not found", probe.next().text());
    }

    @Test
    void aPermittedConsoleAndACommandBlockCanPingAnotherPlayer() throws Exception {
        for (final Class<? extends CommandSender> type : List.of(ConsoleCommandSender.class, BlockCommandSender.class)) {
            final ReplyProbe probe = new ReplyProbe();
            final Player bob = player(probe, "Bob", 120, "fabric");
            final CommandSender sender = probe.sender(type, true);

            withServer(serverWith(bob), () ->
                assertTrue(command(probe, p -> "Jakarta, Indonesia").execute(sender, "ping", new String[] {"Bob"})));

            final String all = plainReceived(probe.received());
            assertTrue(all.contains("Connection / Bob"), all);
            assertTrue(all.contains("120ms"), all);
            assertTrue(all.contains("Client: fabric  (protocol 770)"), all);
            assertTrue(all.contains("Location: Jakarta, Indonesia"), type.getSimpleName() + ": " + all);
        }
    }

    @Test
    void aClientWithoutABrandIsReportedAsVanilla() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final Player alice = player(probe, "Alice", 10, null);
        withServer(serverWith(alice), () -> command(probe, p -> null).execute(alice, "ping", new String[0]));
        assertTrue(plainReceived(probe.received()).contains("Client: vanilla  (protocol 770)"));
    }

    @Test
    void theGaugeIsFullAtZeroEmptyFromFiveHundredAndSurvivesNonsense() throws Exception {
        final int[] pings = {0, 250, 500, 5_000, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        final String[] bars = {
            "[" + "█".repeat(18) + "]  0ms",
            "[" + "█".repeat(9) + "·".repeat(9) + "]  250ms",
            "[" + "·".repeat(18) + "]  500ms",
            "[" + "·".repeat(18) + "]  5000ms",
            "[" + "·".repeat(18) + "]  " + Integer.MAX_VALUE + "ms",
            "[" + "█".repeat(18) + "]  -1ms",
            "[" + "█".repeat(18) + "]  " + Integer.MIN_VALUE + "ms"};
        for (int i = 0; i < pings.length; i++) {
            final ReplyProbe probe = new ReplyProbe();
            final Player alice = player(probe, "Alice", pings[i], null);
            final int index = i;
            withServer(serverWith(alice), () -> assertDoesNotThrow(
                () -> command(probe, p -> null).execute(alice, "ping", new String[0])));
            final String all = plainReceived(probe.received());
            assertTrue(all.contains(bars[index]), "ping " + pings[index] + ": " + all);
        }
    }

    @Test
    void everyReplyAfterExecuteReturnsTakesTheSendersHop() throws Exception {
        // A command block's sendMessage throws off its region thread, so the geo line must hop.
        final ReplyProbe probe = new ReplyProbe();
        final Player bob = player(probe, "Bob", 30, null);
        final CommandSender block = probe.sender(BlockCommandSender.class, true);

        withServer(serverWith(bob), () -> command(probe, p -> "Somewhere").execute(block, "ping", new String[] {"Bob"}));

        final List<ReplyProbe.Received> all = probe.received();
        assertEquals(1, probe.hops());
        final ReplyProbe.Received location = all.stream().filter(r -> r.text().equals("  Location: Somewhere")).findFirst().orElseThrow();
        assertTrue(location.throughHop(), "the geo reply must take the hop");
        final ReplyProbe.Received placeholder = all.stream().filter(r -> r.text().contains("looking up...")).findFirst().orElseThrow();
        assertFalse(placeholder.throughHop(), "the synchronous placeholder is sent inline");
        assertTrue(all.indexOf(placeholder) < all.indexOf(location));
    }

    @Test
    void aMissingEmptyOrFailingGeoLookupEndsWithUnavailable() throws Exception {
        final List<Function<Player, String>> lookups = List.of(
            p -> null, p -> "", p -> { throw new IllegalStateException("db unreadable"); });
        for (final Function<Player, String> lookup : lookups) {
            final ReplyProbe probe = new ReplyProbe();
            final Player alice = player(probe, "Alice", 30, null);
            withServer(serverWith(alice), () -> command(probe, lookup).execute(alice, "ping", new String[0]));
            final String all = plainReceived(probe.received());
            assertTrue(all.contains("Location: unavailable"), all);
            assertEquals(1, probe.hops());
        }
    }

    @Test
    void aStoppedOrSaturatedIoExecutorStillEndsInAnAnswerNotAnException() throws Exception {
        // VirtualExecutor.run throws RejectedExecutionException once shutdown() ran or 64 tasks are in
        // flight. /ping used to let that escape after it had already printed "looking up...".
        final ReplyProbe probe = new ReplyProbe();
        final Player alice = player(probe, "Alice", 30, null);
        final Executor rejecting = task -> { throw new RejectedExecutionException("SourbyCraft I/O capacity reached"); };
        final PingCommand command = quietDenial(new PingCommand("ping", rejecting, p -> "never", probe));

        withServer(serverWith(alice), () -> assertDoesNotThrow(() -> command.execute(alice, "ping", new String[0])));

        final String all = plainReceived(probe.received());
        assertTrue(all.contains("Location: unavailable"), all);
        assertTrue(all.indexOf("looking up...") < all.indexOf("Location: unavailable"),
            "the answer follows the placeholder: " + all);
        assertEquals(1, all.split("Location: unavailable", -1).length - 1, all);
    }
    private static dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime leases() throws Exception {
        var constructor=dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.class.getDeclaredConstructor();
        constructor.setAccessible(true);return constructor.newInstance();
    }
    private static void join(dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime runtime,Player player) {
        runtime.joined(new org.bukkit.event.player.PlayerJoinEvent(player,net.kyori.adventure.text.Component.empty()));
    }
    private static void quit(dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime runtime,Player player) {
        runtime.quit(new org.bukkit.event.player.PlayerQuitEvent(player,net.kyori.adventure.text.Component.empty()));
    }

    @Test void nativeSnapshotsTargetThenHopsBackToDistinctSender() throws Exception {
        var probe=new ReplyProbe();var target=player(probe,"Bob",35,"fabric");
        when(target.getUniqueId()).thenReturn(java.util.UUID.randomUUID());when(target.isOnline()).thenReturn(true);
        var sender=probe.sender(BlockCommandSender.class,true);var runtime=leases();join(runtime,target);
        var targetTasks=new java.util.ArrayList<Runnable>();var senderTasks=new java.util.ArrayList<Runnable>();
        var onTarget=new java.util.concurrent.atomic.AtomicBoolean();
        when(target.getPing()).thenAnswer(invocation -> {assertTrue(onTarget.get(),"player read outside target owner");return 35;});
        var pending=new java.util.concurrent.CompletableFuture<java.util.Optional<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample>>();
        var command=new PingCommand("ping",INLINE,p -> {assertTrue(onTarget.get());return () -> "Local network";},
            (sink,task) -> senderTasks.add(task),(p,task) -> targetTasks.add(task),id -> pending,true);
        try {
            withServer(serverWith(target),() -> command.execute(sender,"ping",new String[]{"Bob"}));
            org.mockito.Mockito.verify(target,org.mockito.Mockito.never()).getPing();
            assertTrue(probe.received().isEmpty());
            onTarget.set(true);targetTasks.removeFirst().run();onTarget.set(false);
            pending.complete(java.util.Optional.empty());assertTrue(probe.received().isEmpty());
            senderTasks.removeFirst().run();senderTasks.removeFirst().run();
            assertTrue(plainReceived(probe.received()).contains("35ms"));
        }finally{quit(runtime,target);}
    }

    @Test void nativeDisconnectedTargetAndSenderSuppressStaleReplies() throws Exception {
        for(boolean targetLeaves:new boolean[]{true,false}) {
            var probe=new ReplyProbe();var target=player(probe,"Bob",35,null);var sender=player(probe,"Alice",20,null);
            for(var p:List.of(target,sender)){when(p.getUniqueId()).thenReturn(java.util.UUID.randomUUID());when(p.isOnline()).thenReturn(true);}
            var runtime=leases();join(runtime,target);join(runtime,sender);
            var replies=new java.util.ArrayList<Runnable>();
            var pending=new java.util.concurrent.CompletableFuture<java.util.Optional<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample>>();
            var command=new PingCommand("ping",INLINE,p -> () -> null,(sink,task) -> replies.add(task),
                (p,task) -> task.run(),id -> pending,true);
            try {
                withServer(serverWith(target),() -> command.execute(sender,"ping",new String[]{"Bob"}));
                quit(runtime,targetLeaves?target:sender);pending.complete(java.util.Optional.empty());
                replies.removeFirst().run();assertTrue(probe.received().isEmpty(),"stale reply after disconnect");
            }finally{quit(runtime,target);quit(runtime,sender);}
        }
    }

    @Test void proxyResultIsPrimaryAndFailureUsesLabelledBackendFallback() throws Exception {
        for(boolean available:new boolean[]{true,false}) {
            var probe=new ReplyProbe();var target=player(probe,"Bob",72,"vanilla");
            var id=java.util.UUID.randomUUID();when(target.getUniqueId()).thenReturn(id);
            var sender=probe.sender(ConsoleCommandSender.class,true);
            var pending=new java.util.concurrent.CompletableFuture<java.util.Optional<dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample>>();
            var command=new PingCommand("ping",INLINE,p -> () -> null,probe,(p,task) -> task.run(),uuid -> pending,false);
            withServer(serverWith(target),() -> command.execute(sender,"ping",new String[]{"Bob"}));
            if(available)pending.complete(java.util.Optional.of(new dev.iyanz.sourbycraft.abysslink.AbyssLinkClient.Sample(id,15,System.currentTimeMillis())));
            else pending.completeExceptionally(new java.io.IOException("proxy unavailable"));
            String text=plainReceived(probe.received());
            assertTrue(text.contains(available?"Client → Leviathan ping: ":"Backend latency: "),text);
            assertTrue(text.contains(available?"15ms":"72ms"),text);
            assertFalse(text.contains("87ms"),"never add control/backend measurements to proxy ping");
        }
    }

}
