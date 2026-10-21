package dev.iyanz.sourbycraft.commandblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The command-block hooks live in a Minecraft feature patch, in code that cannot run without a
 * bootstrapped server. {@link CommandBlockPolicyTest} covers the decisions; this reads the patch
 * text and fails if a hook that applies them goes missing or an inherited unconditional guard
 * comes back. It proves nothing about behaviour; it proves the hooks are where the policy expects.
 */
class CommandBlockPatchShapeTest {

    private static final String POLICY = "dev.iyanz.sourbycraft.commandblock.CommandBlockPolicy";
    private static final String REGIONS = "dev.iyanz.sourbycraft.commandblock.CommandBlockRegions";

    private static String patch() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of("minecraft-patches/features", "sourbycraft-server/minecraft-patches/features")) {
                final Path features = dir.resolve(base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString()
                            .contains("-SourbyCraft-owned-command-blocks")).findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("owned command blocks feature patch not found from " + Path.of("").toAbsolutePath());
    }

    /** One file's whole diff section, context lines included. */
    private static String section(final String patch, final String file) {
        final int start = patch.indexOf("diff --git a/" + file + " ");
        assertTrue(start >= 0, "patch touches " + file);
        final int next = patch.indexOf("\ndiff --git ", start + 1);
        return patch.substring(start, next < 0 ? patch.length() : next);
    }

    /** The added lines of one file's diff. */
    private static String added(final String patch, final String file) {
        final int start = patch.indexOf("diff --git a/" + file + " ");
        assertTrue(start >= 0, "patch touches " + file);
        final int next = patch.indexOf("\ndiff --git ", start + 1);
        final String section = patch.substring(start, next < 0 ? patch.length() : next);
        final StringBuilder out = new StringBuilder();
        for (final String line : section.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** The removed lines of one file's diff. */
    private static String removed(final String patch, final String file) {
        final int start = patch.indexOf("diff --git a/" + file + " ");
        final int next = patch.indexOf("\ndiff --git ", start + 1);
        final String section = patch.substring(start, next < 0 ? patch.length() : next);
        final StringBuilder out = new StringBuilder();
        for (final String line : section.split("\n")) {
            if (line.startsWith("-") && !line.startsWith("---")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static int count(final String text, final String needle) {
        int count = 0;
        for (int from = 0, i; (i = text.indexOf(needle, from)) >= 0; from = i + 1) {
            count++;
        }
        return count;
    }

    @Test
    void theInheritedNeverRunGuardsAreReplacedByTheSwitch() throws IOException {
        final String patch = patch();
        final String block = "net/minecraft/world/level/block/CommandBlock.java";
        assertEquals(2, count(removed(patch, block), "if (true) {"), "tick and GUI guards removed");
        assertEquals(2, count(added(patch, block), "if (!" + POLICY + ".enabled()) {"), "both now read the switch");
        final String base = "net/minecraft/world/level/BaseCommandBlock.java";
        assertTrue(removed(patch, base).contains("if (true) return false; // Folia - region threading"));
        assertTrue(added(patch, base).contains("if (" + POLICY + ".decide(this.sourbyOwnedByCurrentThread()) != "
            + POLICY + ".Decision.RUN) return false;"), "performCommand runs only on RUN");
        final String minecart = "net/minecraft/world/entity/vehicle/minecart/MinecartCommandBlock.java";
        assertTrue(removed(patch, minecart).contains("if (true) {"));
        assertTrue(added(patch, minecart).contains("if (!" + POLICY + ".enabled()) {"));
    }

    @Test
    void theCommandRunsInsideAScopeAndOwnershipIsPerSubclass() throws IOException {
        final String patch = patch();
        final String base = added(patch, "net/minecraft/world/level/BaseCommandBlock.java");
        final String section = section(patch, "net/minecraft/world/level/BaseCommandBlock.java");
        final int scope = section.indexOf("+                    try (" + POLICY + ".Scope scope = " + POLICY + ".enter())");
        final int dispatch = section.indexOf(" level.getServer().getCommands().performPrefixedCommand(commandSourceStack, event.getCommand());");
        final int close = section.indexOf("+                    } // SourbyCraft - owned command blocks");
        assertTrue(scope >= 0 && dispatch > scope && close > dispatch, "dispatch is inside the scope");
        assertTrue(base.contains("public boolean sourbyOwnedByCurrentThread() {\n+        return false;"),
            "unknown subclasses never own");
        final int sink = base.indexOf("if (this.trackOutput && " + POLICY + ".toDiagnostics(" + POLICY
            + ".enabled(), this.closed, BaseCommandBlock.this.sourbyOwnedByCurrentThread())) {");
        assertTrue(sink >= 0, "late or foreign output goes to the diagnostics sink");
        assertTrue(base.indexOf("CommandBlockDiagnostics.global().report(BaseCommandBlock.this.sourbyDescribe(), "
            + "BaseCommandBlock.this.getCommand(), message.getString());") > sink);
        assertTrue(base.contains("return this.trackOutput && (!this.closed || " + POLICY + ".enabled());"),
            "a closed source still accepts failures when on, so they reach the sink");
        assertTrue(base.contains("private volatile boolean closed;"));
        assertTrue(added(patch, "net/minecraft/world/level/block/entity/CommandBlockEntity.java")
            .contains("TickThread.isTickThreadFor(serverLevel, CommandBlockEntity.this.worldPosition)"));
        assertTrue(added(patch, "net/minecraft/world/entity/vehicle/minecart/MinecartCommandBlock.java")
            .contains("TickThread.isTickThreadFor(MinecartCommandBlock.this)"));
        assertTrue(added(patch, "net/minecraft/world/level/block/CommandBlock.java")
            .contains("if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, pos)) break;"),
            "a chain stops at the region edge before reading the next block");
    }

    @Test
    void editPacketsStayRefusedWhenOffAndCheckOwnershipWhenOn() throws IOException {
        final String patch = patch();
        final String file = "net/minecraft/server/network/ServerGamePacketListenerImpl.java";
        assertEquals(2, count(removed(patch, file), "if (true) {"));
        final String added = added(patch, file);
        assertEquals(2, count(added, "if (!" + POLICY + ".enabled()) {"));
        assertTrue(added.contains("TickThread.isTickThreadFor(this.player.level(), packet.getPos())"));
        assertTrue(added.contains("commandBlock != null && !commandBlock.sourbyOwnedByCurrentThread()"));
    }

    @Test
    void selectorsAndBlockTargetsGoThroughTheRegionHooks() throws IOException {
        final String patch = patch();
        final String selector = added(patch, "net/minecraft/commands/arguments/selector/EntitySelector.java");
        assertTrue(selector.contains("return " + REGIONS + ".selector(Util.allOf(completePredicates));"));
        assertEquals(4, count(selector, REGIONS + ".single("), "two name, two UUID lookups");

        final Map<String, String> targets = new LinkedHashMap<>();
        targets.put("dev/iyanz/aurora/engine/threadedregions/commands/AbstractCommandExecution.java", ".requireOwnedUnchecked(level, pos, ");
        targets.put("net/minecraft/server/commands/FillCommand.java", ".loadChunksThenRun(level,");
        targets.put("net/minecraft/server/commands/FillBiomeCommand.java", ".loadChunksThenRun(level,");
        targets.put("net/minecraft/server/commands/CloneCommands.java", ".requireOwned(toDimension, (destination.minX() - 32) >> 4");
        targets.put("net/minecraft/server/commands/data/BlockDataAccessor.java", ".requireOwnedUnchecked(entityLevel, pos, 0");
        for (final Map.Entry<String, String> target : targets.entrySet()) {
            assertTrue(added(patch, target.getKey()).contains(REGIONS + target.getValue()), target.getKey());
        }
        assertEquals(2, count(added(patch, "dev/iyanz/aurora/engine/threadedregions/commands/AbstractCommandExecution.java"),
            ".requireOwnedUnchecked("), "executeAtPos and executeAtPosWithRadius");
    }

    @Test
    void theLevelSwitchReadsTheFlagThenVanillasGamerule() throws IOException {
        final String patch = patch();
        final String file = "net/minecraft/server/level/ServerLevel.java";
        assertTrue(removed(patch, file).contains("return false; // Canvas - region threading"));
        assertTrue(added(patch, file).contains("return " + POLICY
            + ".enabled() && this.getGameRules().get(GameRules.COMMAND_BLOCKS_WORK);"));
    }

    @Test
    void deferredMutationsAreNeverHandedToALaterCallback() throws IOException {
        final String patch = patch();
        for (final String file : List.of("net/minecraft/server/commands/FillCommand.java", "net/minecraft/server/commands/FillBiomeCommand.java")) {
            assertTrue(removed(patch, file).contains("level.moonrise$loadChunksAsync("), file + " no longer calls the engine directly");
            assertTrue(added(patch, file).contains("region.minX() >> 4, region.maxX() >> 4, region.minZ() >> 4, region.maxZ() >> 4,"),
                file + " passes the mutated chunks separately from the buffered footprint");
        }
        assertTrue(section(patch, "net/minecraft/server/commands/FillCommand.java").contains("(region.minX() - buffer) >> 4"),
            "the buffered footprint is what ownership is checked against");
        final String clone = added(patch, "net/minecraft/server/commands/CloneCommands.java");
        assertTrue(clone.contains(".requireOwned(fromDimension, (from.minX() - 32) >> 4"), "clone checks the buffer it loads");
    }

    @Test
    void selectorsCheckOwnershipBeforeTheBoundingBox() throws IOException {
        final String section = section(patch(), "net/minecraft/commands/arguments/selector/EntitySelector.java");
        final int own = section.indexOf("+                if (!" + REGIONS + ".mayRead(entity)) return false;");
        final int bbox = section.indexOf("entity.getBoundingBox().intersects(absoluteAABB)");
        assertTrue(own >= 0 && bbox > own, "ownership before the bounding box");
    }

    @Test
    void aConditionalBlockChecksItsPredecessorsOwnerFirst() throws IOException {
        final String section = section(patch(), "net/minecraft/world/level/block/entity/CommandBlockEntity.java");
        final int own = section.indexOf("+            if (!" + REGIONS + ".mayReadPredecessor(this.level, relative)) {");
        final int read = section.indexOf("if (this.level.getBlockState(relative).getBlock() instanceof CommandBlock) {");
        assertTrue(own >= 0 && read > own, "ownership before the predecessor is read");
        assertTrue(section.indexOf("+                this.conditionMet = false;", own) > own);
    }

    @Test
    void noHookIsUnconditional() throws IOException {
        assertFalse(patch().contains("\n+        if (true)"), "the patch must not add a new unconditional guard");
    }
}
