package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the call sites of Minecraft feature patches 0059 to 0062 and Paper feature patch 0018 (the owned
 * replacements for inherited guards, ledger rows 5 to 14): each hook is where the owned class expects it, every
 * removed line is one of the inherited guard lines and not an unrelated change, and no patch adds the bare legacy
 * message back. It proves nothing about behaviour; the tests of the owned classes do that.
 */
class GuardsPatchShapeTest {

    private static final String LEGACY = "new UnsupportedOperationException(\"Unsupported in region threading\")";
    private static final Pattern REFUSAL = Pattern.compile("RegionRefusal\\.([A-Z_]+)\\.error\\(\\)");

    private static String patch(final String base, final String nameFragment) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String prefix : List.of("", "sourbycraft-server/")) {
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

    private static String mc(final String nameFragment) throws IOException {
        return patch("minecraft-patches/features", nameFragment);
    }

    private static String paper(final String nameFragment) throws IOException {
        return patch("paper-patches/features", nameFragment);
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

    private static Map<RegionRefusal, Integer> refusals(final String patch) {
        final Map<RegionRefusal, Integer> counts = new EnumMap<>(RegionRefusal.class);
        for (final String line : added(patch)) {
            final Matcher m = REFUSAL.matcher(line);
            while (m.find()) {
                counts.merge(RegionRefusal.valueOf(m.group(1)), 1, Integer::sum);
            }
        }
        return counts;
    }

    @Test
    void theServerQueueIsTheUndrainedOneAndNothingElseChanges() throws IOException {
        final String patch = mc("-SourbyCraft-fail-loudly-when-work-is-queued");
        assertEquals(1, files(patch).size());
        assertTrue(files(patch).get(0).contains("net/minecraft/server/MinecraftServer.java"));
        assertEquals(1, added(patch).size());
        // Assigned in the constructor, not by changing the field declaration: patch_policy flags a new
        // container initialiser on MinecraftServer, and the field default is Paper's to keep.
        assertTrue(added(patch).get(0).startsWith("        this.processQueue = dev.iyanz.sourbycraft.guards.UndrainedQueue.create();"), added(patch).get(0));
        assertTrue(removed(patch).isEmpty(), "nothing is removed: " + removed(patch));
        assertTrue(patch.contains("         SERVER = this; // Paper - better singleton"), "right after the singleton is set, before anything can queue");
    }

    @Test
    void everyMinecraftServerRefusalNowNamesItsAlternative() throws IOException {
        final String patch = mc("-SourbyCraft-owned-refusal-messages-for-main-thread-only");
        assertEquals(1, files(patch).size());
        assertTrue(added(patch).stream().noneMatch(l -> l.contains(LEGACY)), "the bare legacy message must not come back");
        assertTrue(removed(patch).stream().allMatch(l -> l.contains(LEGACY)), "only legacy throws are replaced: " + removed(patch));
        assertEquals(removed(patch).size(), added(patch).size(), "one replacement per removed throw");
        final Map<RegionRefusal, Integer> expected = new EnumMap<>(RegionRefusal.class);
        expected.put(RegionRefusal.SERVER_QUEUE, 11); // submit x2, schedule, executeBlocking, execute, wrapRunnable, shouldRun, pollTask, pollTaskInternal, doRunTask, executeIfPossible
        expected.put(RegionRefusal.TICK_LOOP, 1);
        expected.put(RegionRefusal.ADD_TICKABLE, 1);
        expected.put(RegionRefusal.TICK_COUNT, 1);
        assertEquals(expected, refusals(patch));
    }

    @Test
    void collisionTeamAndConversationInputAreOneLineHooks() throws IOException {
        final String patch = mc("-SourbyCraft-player-collision-team-on-the-global-tick");
        assertEquals(3, files(patch).size());
        assertTrue(files(patch).stream().anyMatch(f -> f.contains("net/minecraft/world/scores/Scoreboard.java")));
        assertEquals(1, added(patch).stream().filter(l -> l.contains("public boolean addPlayerToTeamIfAbsent(final String player, final PlayerTeam team)")).count());
        final List<String> added = added(patch);
        assertEquals(1, added.stream().filter(l -> l.contains("PlayerCollisionTeam.join(this, player, io.papermc.paper.threadedregions.RegionizedServer.getInstance()::addTask);")).count());
        assertEquals(1, added.stream().filter(l -> l.contains("PlayerCollisionTeam.quit(this, player, io.papermc.paper.threadedregions.RegionizedServer.getInstance()::addTask);")).count());
        assertEquals(1, added.stream().filter(l -> l.contains("PlayerCollisionTeam.shutdown(this);")).count());
        assertEquals(1, added.stream().filter(l -> l.contains("ConversationInput.deliver(this.player, rawMessage);")).count());
        // The three Folia-disabled collision blocks and the dead conversation queue are what goes.
        final List<String> removed = removed(patch);
        assertEquals(3, removed.stream().filter(l -> l.contains("if (false && this.collideRuleTeamName")).count());
        assertEquals(1, removed.stream().filter(l -> l.contains("this.server.processQueue.add(")).count());
        assertTrue(added.stream().noneMatch(l -> l.contains("processQueue")), "nothing new goes through the undrained queue");
    }

    @Test
    void theEndPlatformEventIsGatedAndTheRegionStatPublishesTickers() throws IOException {
        final String patch = mc("-SourbyCraft-end-platform-portal-event-and-tickable");
        assertEquals(4, files(patch).size());
        final List<String> added = added(patch);
        assertEquals(1, added.stream().filter(l -> l.contains("EndPlatformEvents.shouldFire(entity, newLevel, origin)")).count());
        assertEquals(1, removed(patch).stream().filter(l -> l.contains("if (false) { // Folia - region threading")).count());
        assertEquals(1, added.stream().filter(l -> l.contains("public int getTickableBlockEntityCount()")).count());
        assertEquals(1, added.stream().filter(l -> l.contains("public void publishTickableBlockEntityCount(final int count)")).count());
        // Published by the region after it ticks its block entities, not only when an entity or chunk changes.
        assertTrue(added.stream().noneMatch(l -> l.contains("updateFrom") || l.contains("data.getBlockEntityTickers()")), "no publication through updateFrom");
        final int levelFile = patch.indexOf("diff --git a/net/minecraft/world/level/Level.java");
        assertTrue(levelFile >= 0, "Level.tickBlockEntities is hooked");
        final int setTicking = patch.indexOf("regionizedWorldData.setTickingBlockEntities(false);", levelFile);
        final int publish = patch.indexOf("+        regionizedWorldData.regionData.getRegionStats().publishTickableBlockEntityCount(blockEntityTickers.size());", levelFile);
        assertTrue(setTicking >= 0 && publish > setTicking, "published right after the ticking loop ends");
        // The portal path that every region-threaded End trip takes hands over the portaling entity.
        assertEquals(1, removed(patch).stream().filter(l -> l.contains("createEndPlatform(destination, targetPos.below(), true, null);")).count());
        assertEquals(1, added.stream().filter(l -> l.contains("createEndPlatform(destination, targetPos.below(), true, this);")).count());
    }

    @Test
    void paperRefusalsNameTheirAlternativeAndEveryOwnedMessageIsUsedSomewhere() throws IOException {
        final String patch = paper("-SourbyCraft-owned-refusal-messages-and-off-region-tick-time");
        assertTrue(added(patch).stream().noneMatch(l -> l.contains(LEGACY)), "the bare legacy message must not come back");
        assertTrue(removed(patch).stream().allMatch(l -> l.contains(LEGACY) || l.contains("TODO fix this")), "only legacy throws are replaced: " + removed(patch));
        final Map<RegionRefusal, Integer> expected = new EnumMap<>(RegionRefusal.class);
        expected.put(RegionRefusal.PLUGIN_RELOAD, 1);
        expected.put(RegionRefusal.DATA_RELOAD, 1);
        expected.put(RegionRefusal.UNLOAD_WORLD, 1);
        expected.put(RegionRefusal.RESTART, 2); // CraftServer#restart and RestartCommand#restart
        expected.put(RegionRefusal.DATAPACK_TOGGLE, 1);
        expected.put(RegionRefusal.BUKKIT_SCHEDULER, 1);
        expected.put(RegionRefusal.TICK_MANAGER, 13);
        expected.put(RegionRefusal.ENSURE_MAIN, 2);
        assertEquals(expected, refusals(patch));

        final Map<RegionRefusal, Integer> all = new EnumMap<>(refusals(mc("-SourbyCraft-owned-refusal-messages-for-main-thread-only")));
        refusals(patch).forEach((k, v) -> all.merge(k, v, Integer::sum));
        final Set<RegionRefusal> unused = EnumSet.allOf(RegionRefusal.class);
        unused.removeAll(all.keySet());
        assertEquals(Set.of(RegionRefusal.UNDRAINED_QUEUE), unused, "only the queue refusal is raised by owned code instead of a patch");
    }

    @Test
    void offRegionAverageTickTimeAndTheTickableCountAreHooked() throws IOException {
        final String patch = paper("-SourbyCraft-owned-refusal-messages-and-off-region-tick-time");
        final List<String> added = added(patch);
        assertEquals(1, added.stream().filter(l -> l.contains("task = io.papermc.paper.threadedregions.RegionizedServer.getGlobalTickData();")).count());
        assertEquals(1, added.stream().filter(l -> l.contains("return dev.iyanz.sourbycraft.guards.RegionStatistics.tickableBlockEntities(this.world);")).count());
        // The new fallback sits in getAverageTickTime, right before the "Not on any region" check, as it does in getTPS.
        final int fallback = patch.indexOf("task = io.papermc.paper.threadedregions.RegionizedServer.getGlobalTickData();");
        assertTrue(fallback > 0, "fallback present");
        final int check = patch.indexOf("throw new UnsupportedOperationException(\"Not on any region\")", fallback);
        assertTrue(check > fallback && check - fallback < 400, "the region check follows the fallback inside the same hunk");
    }
}
