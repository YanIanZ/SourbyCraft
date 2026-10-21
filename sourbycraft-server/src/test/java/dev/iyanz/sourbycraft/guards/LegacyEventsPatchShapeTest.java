package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** The part of {@code patch} for one file. */
    private static String section(final String patch, final String file) {
        final int start = patch.indexOf("diff --git a/" + file);
        assertTrue(start >= 0, file + " not in the patch");
        final int next = patch.indexOf("diff --git ", start + 1);
        return next < 0 ? patch.substring(start) : patch.substring(start, next);
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
    void theRegionsReadTheServerWideStateAndOnlyTheMarkedGlobalTickAdvancesIt() throws IOException {
        final String patch = patch("minecraft-patches/features", "-SourbyCraft-server-wide-ServerTickManager-state-on-the-global-tick");
        assertEquals(4, files(patch).size());
        final List<String> added = added(patch);
        // Review finding 7: the advance is a scheduler-level boundary in the global tick handle's
        // tickRegion (reached only after tryMarkTicking and the cancelled checks), not a
        // region == null special case inside the shared per-handle tickStart.
        assertEquals(1, count(added, "dev.iyanz.sourbycraft.guards.ServerTickControl.get().globalTick();"), "advanced once per global tick, nowhere else");
        assertTrue(added.stream().noneMatch(l -> l.contains("scheduleHandle.region == null")), "no special case in the shared tickStart");
        final String regionized = section(patch, "io/papermc/paper/threadedregions/RegionizedServer.java");
        final int tickRegion = regionized.indexOf("protected void tickRegion(final long tickCount, final long startTime, final long scheduledEnd) {");
        final int advance = regionized.indexOf("ServerTickControl.get().globalTick();");
        final int recopy = regionized.indexOf("this.getTickManager().copyServerTickState();");
        final int drain = regionized.indexOf("this.drainTasks();");
        assertTrue(tickRegion >= 0 && tickRegion < advance && advance < recopy && recopy < drain,
            "global tickRegion: advance, re-copy, then the global work");
        assertFalse(section(patch, "dev/iyanz/aurora/engine/threadedregions/ScheduledHandleTickState.java").contains("+        dev.iyanz.sourbycraft.guards.ServerTickControl.get().globalTick()"));
        assertEquals(1, count(added, "copyServerTickState(); // SourbyCraft - server-wide ServerTickManager state, copied at the start of each tick"));
        assertEquals(1, count(added, "serverRunsGameElements = dev.iyanz.sourbycraft.guards.ServerTickControl.get().isRunningNormally();"));
        assertEquals(1, count(added, "serverSprints = dev.iyanz.sourbycraft.guards.ServerTickControl.get().sprintsThisTick();"));
        assertEquals(1, count(added, "return runsGameElements && serverRunsGameElements;"));
        assertEquals(1, count(added, "if (serverSprints) return 1L;"));
        // Review finding 3/6: no hand-made step or state broadcast; vanilla's manager sends them.
        assertTrue(added.stream().noneMatch(l -> l.contains("ClientboundTickingStepPacket")), "step packets come from vanilla's manager");
        // The two vanilla runsNormally() checks the base made unconditional come back.
        final List<String> removed = removed(patch);
        assertEquals(2, count(removed, "if (true) { // Canvas - region threading"));
        assertEquals(2, count(added, "getRegionSchedulingHandle().getTickManager().doesRunGameElements()) { // SourbyCraft - frozen ticks"));
        // No new region-scheduler symbol in added lines (test_independence_policy pins the recorded set).
        assertTrue(added.stream().noneMatch(l -> l.contains("TickRegionScheduler") || l.contains("RegionizedServer")), "scheduler symbols: " + added);
    }

    @Test
    void bothLegacyChatEventsFireThroughTheOwnedClassAndTheMessageIsSentOnlyAfterTheEventRan() throws IOException {
        final String patch = patch("paper-patches/features", "-SourbyCraft-legacy-PlayerChatEvent-and-ChatEvent-on-the-sender-s-region");
        assertEquals(1, files(patch).size());
        assertTrue(files(patch).get(0).contains("io/papermc/paper/adventure/ChatProcessor.java"));
        final List<String> added = added(patch);
        final List<String> removed = removed(patch);
        assertEquals(2, count(removed, "if (false && listenersOnSyncEvent) { // Folia - region threading"));
        assertEquals(2, count(added, "if (listenersOnSyncEvent) { // SourbyCraft - legacy"));
        assertEquals(1, count(removed, "this.server.processQueue.add(waitable);"));
        // Review finding 1: the decision is by the calling thread, not Paper's async flag.
        assertEquals(1, count(added, "dev.iyanz.sourbycraft.guards.LegacyChat.onSenderRegion(this.player, fire, deliver);"));
        assertTrue(added.stream().noneMatch(l -> l.contains("this.async, ")), "the async flag no longer decides: " + added);
        assertEquals(2, count(added, "this.onSenderRegion(() -> ChatProcessor.this.post(chatEvent), () -> {"));
        // Review finding 4: complete() is in the deliver step, never inside the hop to the region.
        assertTrue(added.stream().noneMatch(l -> l.contains("Waitable")), "no Waitable left: " + added);
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
        // Review finding 5: isFrozen(Entity) asks the owning region (its pause + server-wide state) and
        // never reads passengers in the patch line; ServerTickControl decides by ownership.
        assertEquals(1, count(added, "ServerTickControl.get().isEntityFrozen(handle, owned -> owned.level().getCurrentWorldData().regionData.getRegionSchedulingHandle().getTickManager().isEntityFrozen(owned));"));
        assertTrue(added.stream().noneMatch(l -> l.contains("countPlayerPassengers")), "no off-owner passenger read in the patch: " + added);
    }

    @Test
    void theApiNoLongerMarksTheTickManagerUnsupported() throws IOException {
        final String patch = patch("sourbyapi/paper-patches/features", "-SourbyCraft-ServerTickManager-is-supported");
        assertEquals(1, count(removed(patch), "@Unsupported // Canvas - region threading"));
        assertTrue(added(patch).stream().noneMatch(l -> l.contains("@Unsupported")));
    }
}
