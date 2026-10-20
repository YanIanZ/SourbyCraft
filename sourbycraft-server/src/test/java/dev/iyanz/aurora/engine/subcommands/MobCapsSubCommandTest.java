package dev.iyanz.aurora.engine.subcommands;

import com.mojang.brigadier.CommandDispatcher;
import dev.iyanz.aurora.engine.commands.SubCommand;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.bukkit.entity.SpawnCategory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.assertGuardedByCanvasNode;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.dispatcher;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.executable;
import static dev.iyanz.aurora.engine.subcommands.SubCommandTestSupport.suggestions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine subcommands: {@link MobCapsSubCommand} ({@code mobcaps get|set|reset <category>}).
 * Every branch runs through {@code AbstractCommandExecution.executeOnGlobal} and then reads or writes
 * {@code CraftServer.spawnCategoryLimit}, i.e. needs a booted server, so execution is not covered.
 * Tested: identity, the permission node, the grammar (value at least 0 for {@code set}), and that the
 * category suggestions are exactly the spawn categories except {@code MISC}, in lower case.
 */
class MobCapsSubCommandTest {

    private final SubCommand sub = new MobCapsSubCommand();
    private final CommandSourceStack source = new SubCommandTestSupport.RecordingSource(true).stack;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identityAndPermission() {
        assertEquals("mobcaps", this.sub.getName());
        assertFalse(this.sub.getDescription().isBlank());
        assertTrue(this.sub.isAllowedSelfCommand());
        assertGuardedByCanvasNode(this.sub, "canvas.command.mobcaps");
    }

    @Test
    void getAndResetTakeACategoryAndSetTakesACategoryAndANonNegativeValue() {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);

        assertTrue(executable(d, "mobcaps get monster", this.source));
        assertTrue(executable(d, "mobcaps reset animal", this.source));
        assertTrue(executable(d, "mobcaps set monster 70", this.source));
        assertTrue(executable(d, "mobcaps set monster 0", this.source));

        assertFalse(executable(d, "mobcaps", this.source));
        assertFalse(executable(d, "mobcaps get", this.source));
        assertFalse(executable(d, "mobcaps reset", this.source));
        assertFalse(executable(d, "mobcaps set monster", this.source), "value is required");
        assertFalse(executable(d, "mobcaps set monster -1", this.source), "negative limit");
        assertFalse(executable(d, "mobcaps set monster many", this.source));
        assertFalse(executable(d, "mobcaps get monster extra", this.source));
        assertFalse(executable(d, "mobcaps list", this.source));
    }

    @Test
    void anyWordParsesAsACategoryAndIsRejectedLaterAtExecution() {
        // by design the parser takes a plain word; unknown names fail with "Unknown category" when run
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        assertTrue(executable(d, "mobcaps get notacategory", this.source));
    }

    @Test
    void categorySuggestionsAreEverySpawnCategoryButMisc() throws Exception {
        final CommandDispatcher<CommandSourceStack> d = dispatcher(this.sub);
        final List<String> expected = new TreeSet<>(Arrays.stream(SpawnCategory.values())
            .filter(category -> category != SpawnCategory.MISC)
            .map(category -> category.name().toLowerCase())
            .toList()).stream().toList();

        for (final String verb : List.of("get", "reset", "set")) {
            final List<String> offered = suggestions(d, "mobcaps " + verb + " ", this.source);
            assertEquals(expected, new TreeSet<>(offered).stream().toList(), verb);
            assertFalse(offered.contains("misc"), verb);
        }
        assertEquals(List.of("monster"), suggestions(d, "mobcaps get mon", this.source));
    }

    @Test
    void verbsAreSuggested() throws Exception {
        assertEquals(List.of("get", "reset", "set"), suggestions(dispatcher(this.sub), "mobcaps ", this.source));
    }
}
