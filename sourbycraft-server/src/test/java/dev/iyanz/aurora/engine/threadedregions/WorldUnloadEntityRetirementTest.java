package dev.iyanz.aurora.engine.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.threadedregions.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.FoliaEntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ENG-3, the entity half: a world unload retires the schedulers of entities that are saved, not
 * removed. Every pending entity task must get exactly its retired callback (or nothing without
 * one), never its body, whatever its due tick, and nothing may run once the drain returned. Uses
 * the real Paper {@link EntityScheduler} and {@link FoliaEntityScheduler} (Paper feature patch
 * 0030) over a mocked entity.
 */
@org.bukkit.support.environment.Normal
class WorldUnloadEntityRetirementTest {

    /** An hour of ticks: never due during the test. */
    private static final long FAR = 72_000L;

    private final List<String> ran = new ArrayList<>();
    private final List<String> retired = new ArrayList<>();
    private Entity handle;
    private EntityScheduler scheduler;
    private FoliaEntityScheduler api;
    private Plugin plugin;

    @BeforeEach
    void entity() throws ReflectiveOperationException {
        this.handle = mock(Entity.class); // isRemoved() == false: saved by the unload, not removed
        final CraftEntity craft = mock(CraftEntity.class);
        when(craft.getHandleRaw()).thenReturn(this.handle);
        this.scheduler = new EntityScheduler(craft);
        // A mock skips CraftEntity's constructor, which creates the scheduler the API task consults.
        final Field field = CraftEntity.class.getField("taskScheduler");
        field.setAccessible(true);
        field.set(craft, this.scheduler);
        this.api = new FoliaEntityScheduler(craft);
        this.plugin = mock(Plugin.class);
        when(this.plugin.isEnabled()).thenReturn(true);
        when(this.plugin.getLogger()).thenReturn(Logger.getLogger("WorldUnloadEntityRetirementTest"));
        when(this.plugin.getDescription()).thenReturn(new org.bukkit.plugin.PluginDescriptionFile("UnloadTest", "1", "unload.Test"));
    }

    private Runnable retiredAs(final String name) {
        return () -> this.retired.add(name);
    }

    @Test
    void unloadRunsEveryRetiredCallbackOnceAndNoBody() {
        final List<ScheduledTask> tasks = new ArrayList<>();
        tasks.add(this.api.runDelayed(this.plugin, t -> this.ran.add("delayed"), this.retiredAs("delayed"), FAR));
        tasks.add(this.api.runAtFixedRate(this.plugin, t -> this.ran.add("repeating"), this.retiredAs("repeating"), FAR, 20L));
        tasks.add(this.api.run(this.plugin, t -> this.ran.add("next-tick"), this.retiredAs("next-tick")));
        tasks.add(this.api.runDelayed(this.plugin, t -> this.ran.add("no-callback"), null, FAR));
        assertTrue(this.api.execute(this.plugin, () -> this.ran.add("execute"), this.retiredAs("execute"), 5L));
        assertTrue(this.api.execute(this.plugin, () -> this.ran.add("execute-no-callback"), null, FAR));

        final List<Throwable> errors = new ArrayList<>();
        assertEquals(1, WorldUnloadDrain.retireSchedulers(new EntityScheduler[] {this.scheduler}, errors::add));

        assertEquals(List.of(), this.ran, "no body runs at the unload, due or not");
        assertEquals(List.of("delayed", "execute", "next-tick", "repeating"), this.retired.stream().sorted().toList());
        assertEquals(4, this.retired.size(), "each retired callback exactly once");
        assertEquals(List.of(), errors);
        for (final ScheduledTask task : tasks) {
            assertEquals(ScheduledTask.ExecutionState.CANCELLED, task.getExecutionState());
        }
        assertTrue(this.scheduler.isRetired());

        // Nothing can be queued on, or run from, the retired scheduler afterwards.
        assertNull(this.api.runDelayed(this.plugin, t -> this.ran.add("late"), this.retiredAs("late"), 1L));
        assertFalse(this.api.execute(this.plugin, () -> this.ran.add("late"), this.retiredAs("late"), 1L));
        assertEquals(List.of(), this.ran);
        assertEquals(4, this.retired.size());
        assertEquals(0, WorldUnloadDrain.retireSchedulers(new EntityScheduler[] {this.scheduler}, errors::add),
            "an already retired scheduler is skipped, its callbacks do not run twice");
        assertEquals(4, this.retired.size());
    }

    @Test
    void aRemovedEntityStillGetsItsRetiredCallback() {
        when(this.handle.isRemoved()).thenReturn(true);
        this.api.runDelayed(this.plugin, t -> this.ran.add("delayed"), this.retiredAs("delayed"), FAR);
        this.scheduler.retire();
        assertEquals(List.of(), this.ran);
        assertEquals(List.of("delayed"), this.retired);
    }

    @Test
    void aThrowingRetiredCallbackDoesNotStopTheOtherSchedulers() throws ReflectiveOperationException {
        this.api.runDelayed(this.plugin, t -> this.ran.add("a"), () -> { throw new IllegalStateException("plugin bug"); }, FAR);
        final WorldUnloadEntityRetirementTest other = new WorldUnloadEntityRetirementTest();
        other.entity();
        other.api.runDelayed(other.plugin, t -> other.ran.add("b"), other.retiredAs("b"), FAR);
        // FoliaEntityScheduler catches a plugin's exception itself; a scheduler-level failure is
        // simulated with a raw task whose retired consumer throws.
        final EntityScheduler failing = new EntityScheduler(mock(CraftEntity.class));
        failing.schedule(e -> this.ran.add("raw"), e -> { throw new IllegalStateException("raw"); }, FAR);

        final List<Throwable> errors = new ArrayList<>();
        assertEquals(2, WorldUnloadDrain.retireSchedulers(new EntityScheduler[] {this.scheduler, failing, other.scheduler}, errors::add));
        assertEquals(1, errors.size());
        assertEquals(List.of("b"), other.retired);
        assertEquals(List.of(), this.ran);
        assertEquals(List.of(), other.ran);
    }

    @Test
    void theApiTaskConsultsTheSchedulersRetirement() throws IOException {
        // Bytecode: EntityScheduledTask.accept asks EntityScheduler.isRetired() (Paper feature patch 0030).
        final String inner = FoliaEntityScheduler.class.getName() + "$EntityScheduledTask";
        final List<String> calls = new ArrayList<>();
        try (InputStream input = FoliaEntityScheduler.class.getResourceAsStream("/" + inner.replace('.', '/') + ".class")) {
            assertTrue(input != null);
            for (final MethodModel method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if (!method.methodName().stringValue().equals("accept")) continue;
                for (final CodeElement element : method.code().orElseThrow()) {
                    if (element instanceof InvokeInstruction invoke) {
                        calls.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue());
                    }
                }
            }
        }
        assertTrue(calls.contains("io/papermc/paper/threadedregions/EntityScheduler.isRetired"), calls.toString());
        assertTrue(calls.contains("net/minecraft/world/entity/Entity.isRemoved"), calls.toString());
    }

    @Test
    void thePatchIsOneMarkedLineInFoliaEntityScheduler() throws IOException {
        final String patch = featurePatch("-SourbyCraft-retired-entity-scheduler-runs-the-retired-callback");
        assertEquals(1, count(patch, "diff --git"), "the patch touches only FoliaEntityScheduler");
        assertEquals(1, count(patch, "+++ b/src/main/java/io/papermc/paper/threadedregions/scheduler/FoliaEntityScheduler.java"));
        assertEquals(1, count(patch,
            "\n+            final boolean retired = entity.isRemoved() || FoliaEntityScheduler.this.entity.taskScheduler.isRetired();\n"));
        assertEquals(1, count(patch, "\n-            final boolean retired = entity.isRemoved();\n"));
        int removed = 0;
        for (final String line : patch.split("\n")) {
            if (line.startsWith("-") && !line.startsWith("---")) removed++;
        }
        assertEquals(1, removed, "only the one decision line is replaced");
    }

    /** The Paper feature patch whose name contains {@code nameContains}, searched upward from the working directory. */
    private static String featurePatch(final String nameContains) throws IOException {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (final String base : List.of("paper-patches/features", "sourbycraft-server/paper-patches/features")) {
                final Path features = dir.resolve(base);
                if (!Files.isDirectory(features)) continue;
                try (Stream<Path> files = Files.list(features)) {
                    final Path found = files.filter(p -> p.getFileName().toString().contains(nameContains)).findFirst().orElse(null);
                    if (found != null) return Files.readString(found);
                }
            }
        }
        throw new AssertionError("no Paper feature patch named *" + nameContains + "*");
    }

    private static int count(final String text, final String needle) {
        int n = 0;
        for (int from = 0, i; (i = text.indexOf(needle, from)) >= 0; from = i + 1) n++;
        return n;
    }
}
