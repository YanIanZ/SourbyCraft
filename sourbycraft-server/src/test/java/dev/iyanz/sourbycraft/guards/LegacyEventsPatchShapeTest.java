package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the call sites of the legacy-event and tick-manager feature patches (Minecraft "legacy
 * PlayerPreLoginEvent on the global tick" and "server-wide ServerTickManager state", Paper "legacy
 * PlayerChatEvent and ChatEvent on the sender's region" and "ServerTickManager backed by
 * server-wide tick state", sourbyapi "ServerTickManager is supported"): each hook is where the owned
 * class expects it, the removed lines are the inherited guards, and nothing goes back through the
 * undrained main-thread queue. Behaviour is tested by the owned classes' tests.
 */
class LegacyEventsPatchShapeTest {

    private static String patch(final String base, final String nameFragment) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String prefix : List.of("", "sourbycraft-server/", "../")) {
                final Path features = dir.resolve(prefix + base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString().contains(nameFragment)).findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("feature patch '*" + nameFragment + "*' not found in " + base + " from " + Path.of("").toAbsolutePath());
    }

    private static List<String> added(final String patch) {
        return patch.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).map(l -> l.substring(1)).collect(Collectors.toList());
    }

    private static List<String> removed(final String patch) {
        return patch.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).map(l -> l.substring(1)).collect(Collectors.toList());
    }

    private static List<String> files(final String patch) {
        return patch.lines().filter(l -> l.startsWith("diff --git ")).collect(Collectors.toList());
    }

    private static long count(final List<String> lines, final String fragment) {
        return lines.stream().filter(l -> l.contains(fragment)).count();
    }

    @Test
    void thePreLoginEventIsGatedOnListenersAndFiredThroughTheOwnedClass() throws IOException {
        final String patch = patch("minecraft-patches/features", "-SourbyCraft-legacy-PlayerPreLoginEvent-on-the-global-tick");
        assertEquals(1, files(patch).size());
        assertTrue(files(patch).get(0).contains("net/minecraft/server/network/ServerLoginPacketListenerImpl.java"));
        final List<String> added = added(patch);
        final List<String> removed = removed(patch);
        assertEquals(1, count(removed, "if (false && PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) { // Folia - region threading"));
        assertEquals(1, count(added, "if (dev.iyanz.sourbycraft.guards.LegacyPreLogin.hasListeners()) {"));
        assertEquals(1, count(added, "dev.iyanz.sourbycraft.guards.LegacyPreLogin.fire(event, server.getPluginManager()::callEvent)"));
        assertEquals(1, count(removed, "processQueue.add(waitable)"), "the undrained main-thread hop goes");
        assertTrue(added.stream().noneMatch(l -> l.contains("processQueue")), "nothing new goes through the undrained queue");
        assertTrue(added.stream().noneMatch(l -> l.contains("if (false")), "no constant guard comes back");
    }

    @Test
    void theRegionsReadTheServerWideStateAndOnlyTheGlobalTickAdvancesIt() throws IOException {
        final String patch = patch("minecraft-patches/features", "-SourbyCraft-server-wide-ServerTickManager-state-on-the-global-tick");
        assertEquals(3, files(patch).size());
        final List<String> added = added(patch);
        assertEquals(1, count(added, "if (scheduleHandle.region == null) { // the global tick handle"));
        assertEquals(1, count(added, "int changes = serverTicks.globalTick();"), "advanced once per global tick, nowhere else");
        assertEquals(1, count(added, "serverRunsGameElements = serverTicks.isRunningNormally();"));
        assertEquals(1, count(added, "serverSprints = serverTicks.sprintsThisTick();"));
        assertEquals(1, count(added, "return runsGameElements && serverRunsGameElements;"));
        assertEquals(1, count(added, "if (serverSprints) return 1L;"));
        // The globalTick call precedes the copy, so the global tick reads what it just published.
        final int advance = patch.indexOf("serverTicks.globalTick()");
        final int copy = patch.indexOf("serverRunsGameElements = serverTicks.isRunningNormally();");
        assertTrue(advance > 0 && copy > advance, "advance, then copy");
        // The two vanilla runsNormally() checks the base made unconditional come back.
        final List<String> removed = removed(patch);
        assertEquals(2, count(removed, "if (true) { // Canvas - region threading"));
        assertEquals(2, count(added, "getRegionSchedulingHandle().getTickManager().doesRunGameElements()) { // SourbyCraft - frozen ticks"));
        // No new region-scheduler symbol in added lines (test_independence_policy pins the recorded set).
        assertTrue(added.stream().noneMatch(l -> l.contains("TickRegionScheduler") || l.contains("RegionizedServer")), "scheduler symbols: " + added);
    }

    @Test
    void bothLegacyChatEventsFireThroughTheOwnedClassAndADropReturns() throws IOException {
        final String patch = patch("paper-patches/features", "-SourbyCraft-legacy-PlayerChatEvent-and-ChatEvent-on-the-sender-s-region");
        assertEquals(1, files(patch).size());
        assertTrue(files(patch).get(0).contains("io/papermc/paper/adventure/ChatProcessor.java"));
        final List<String> added = added(patch);
        final List<String> removed = removed(patch);
        assertEquals(2, count(removed, "if (false && listenersOnSyncEvent) { // Folia - region threading"));
        assertEquals(2, count(added, "if (listenersOnSyncEvent) { // SourbyCraft - legacy"));
        assertEquals(1, count(removed, "this.server.processQueue.add(waitable);"));
        assertEquals(1, count(added, "if (!dev.iyanz.sourbycraft.guards.LegacyChat.onSenderRegion(this.player, this.async, waitable)) {"));
        assertEquals(1, count(added, "})) return; // SourbyCraft - not delivered in time: the message is dropped"),
            "PlayerChatEvent: a message whose event was not delivered is not processed further");
        assertTrue(added.stream().noneMatch(l -> l.contains("processQueue")), "nothing new goes through the undrained queue");
    }

    @Test
    void everyTickManagerMethodDelegatesAndNoRefusalIsLeft() throws IOException {
        final String patch = patch("paper-patches/features", "-SourbyCraft-ServerTickManager-backed-by-server-wide-tick-state");
        assertEquals(1, files(patch).size());
        final List<String> added = added(patch);
        final List<String> removed = removed(patch);
        assertEquals(13, count(removed, "RegionRefusal.TICK_MANAGER.error()"), "all 13 refusals go");
        assertEquals(removed.size(), count(removed, "RegionRefusal.TICK_MANAGER.error()"), "and nothing else: " + removed);
        assertTrue(added.stream().noneMatch(l -> l.contains("RegionRefusal")), "no refusal is left");
        assertEquals(13, added.stream().filter(l -> l.contains("dev.iyanz.sourbycraft.guards.ServerTickControl.get().")).count(),
            "one delegation per method");
        assertEquals(1, count(added, "Preconditions.checkArgument(entity != null"));
    }

    @Test
    void theApiNoLongerMarksTheTickManagerUnsupported() throws IOException {
        final String patch = patch("sourbyapi/paper-patches/features", "-SourbyCraft-ServerTickManager-is-supported");
        assertEquals(1, count(removed(patch), "@Unsupported // Canvas - region threading"));
        assertTrue(added(patch).stream().noneMatch(l -> l.contains("@Unsupported")));
    }
}
