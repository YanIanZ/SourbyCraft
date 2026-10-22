package dev.iyanz.sourbycraft.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.apache.logging.log4j.core.config.plugins.util.PluginType;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

/**
 * The shipped {@code log4j2.xml} (paper feature patch 0016) and the SourbyCraft log4j plugins it
 * uses ({@code %scLogger}, {@code %scLevel}, {@code %scHighlight}, {@code %scDim},
 * {@code %ansiIfTerminal}, {@code %plainText}, {@code RepeatedMessageFilter}). The plugins live in
 * the {@code log4jPlugins} source set, which is on the test runtime classpath only, so they are
 * reached the way log4j reaches them: through patterns and the plugin registry. Tests never run on
 * a terminal, so every rendered line here must be free of escape codes.
 */
class StartupLogLayoutTest {

    private static final String ESC = "\u001B";
    private static final String COLOURED = ESC + "[38;2;34;211;238mSourbyCraft" + ESC + "[0m / Version";

    private static String config() throws IOException {
        try (InputStream in = StartupLogLayoutTest.class.getClassLoader().getResourceAsStream("log4j2.xml")) {
            assertNotNull(in, "log4j2.xml is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The PatternLayout pattern of the appender named {@code appender}, XML-unescaped. */
    private static String pattern(final String appender) throws IOException {
        final String xml = config();
        final int at = xml.indexOf("name=\"" + appender + "\"");
        assertTrue(at >= 0, appender + " appender missing");
        final Matcher m = Pattern.compile("<PatternLayout pattern=\"([^\"]*)\"").matcher(xml);
        assertTrue(m.find(at), appender + " has no PatternLayout pattern");
        return m.group(1).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String render(final String pattern, final String logger, final Level level, final String msg) {
        final PatternLayout layout = PatternLayout.newBuilder()
            .withPattern(pattern).withConfiguration(new DefaultConfiguration()).build();
        return layout.toSerializable(event(logger, level, msg));
    }

    private static LogEvent event(final String logger, final Level level, final String msg) {
        return Log4jLogEvent.newBuilder().setLoggerName(logger).setLevel(level)
            .setThreadName("Server thread").setMessage(new SimpleMessage(msg)).build();
    }

    @Test
    void scLoggerFoldsInternalNamespacesOntoFiveTagsAndKeepsEveryOtherName() {
        final String[][] cases = {
            {"dev.iyanz.sourbycraft.perf.AsyncPathProcessor", "SourbyCraft"},
            {"dev.iyanz.sourbycraft.Foo$Inner", "SourbyCraft"},
            {"dev.iyanz.aurora.engine.GlobalConfiguration", "Aurora"},
            {"io.papermc.paper.threadedregions.TickRegionScheduler$Inner", "Aurora"},
            {"net.minecraft.server.MinecraftServer", "Minecraft"},
            {"com.mojang.authlib.Foo", "Minecraft"},
            {"Minecraft", "Minecraft"},
            {"", "Minecraft"},
            {"io.papermc.paper.plugin.manager.PluginInitializerManager", "Paper"},
            {"ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry", "Paper"},
            {"ca.spottedleaf.moonrise.common.util.MoonriseCommon", "Paper"},
            {"org.bukkit.craftbukkit.CraftServer", "Paper"},
            {"com.destroystokyo.paper.Foo", "Paper"},
            {"org.spigotmc.WatchdogThread", "Paper"},
            {"oshi.software.os.mac.MacOperatingSystem", "OSHI"},
            // Classes that log by simple name (LogUtils.getClassLogger()) or a literal.
            {"PluginInitializerManager", "Paper"},
            {"MoonriseCommon", "Paper"},
            {"ChunkHolderManager", "Paper"},
            {"SpigotLibraryLoader", "Paper"},
            {"TickRegions", "Aurora"},
            {"TickRegionScheduler", "Aurora"},
            {"WorldShutdownThread", "Aurora"},
            {"ConfigurationProvider", "Aurora"},
            {"FoliaAsyncScheduler", "Aurora"},
            // Owned area loggers fold onto the brand tag.
            {"SourbyCraft/AWF", "SourbyCraft"},
            {"SourbyCraft/VirtualExecutor", "SourbyCraft"},
            // The names Paper printed without a prefix (plugins bypassing the plugin logger).
            {"com.sk89q.worldedit.WorldEdit", "WorldEdit"},
            {"ru.tehkode.permissions.Foo$Bar", "Foo"},
            // Unknown names are a plugin's or library's identity: never shortened.
            {"com.example.plugin.Main", "com.example.plugin.Main"},
            {"My.Plugin", "My.Plugin"},
            {"dev.iyanzfake.Other", "dev.iyanzfake.Other"},
            {"SourbyCraft", "SourbyCraft"},
            {"SourbyCraft-Test-Plugin", "SourbyCraft-Test-Plugin"},
            {"Aurora", "Aurora"},
            {"bootstrap", "bootstrap"},
            {"spark", "spark"},
            {"bStats", "bStats"},
        };
        for (final String[] c : cases) {
            assertEquals(c[1], render("%scLogger", c[0], Level.INFO, "m"), c[0]);
            assertEquals("[" + c[1] + "]", render("%scLogger{file}", c[0], Level.INFO, "m"), c[0]);
        }
    }

    @Test
    void scLoggerConsolePadsToTheColumnAndNeverCutsALongTag() {
        assertEquals("SourbyCraft ", render("%scLogger{console}", "dev.iyanz.sourbycraft.X", Level.INFO, "m"));
        assertEquals("Aurora      ", render("%scLogger{console}", "Aurora", Level.INFO, "m"));
        assertEquals("Minecraft   ", render("%scLogger{console}", "net.minecraft.A", Level.INFO, "m"));
        assertEquals("Paper       ", render("%scLogger{console}", "io.papermc.paper.B", Level.INFO, "m"));
        assertEquals("SourbyCraft-Test-Plugin", render("%scLogger{console}", "SourbyCraft-Test-Plugin", Level.INFO, "m"));
        // Not a terminal in tests: the console option must not colour either.
        assertFalse(render("%scLogger{console}", "dev.iyanz.sourbycraft.X", Level.INFO, "m").contains(ESC));
    }

    @Test
    void scLevelIsAFiveCharacterColumn() {
        assertEquals("INFO ", render("%scLevel", "x", Level.INFO, "m"));
        assertEquals("WARN ", render("%scLevel{console}", "x", Level.WARN, "m"));
        assertEquals("ERROR", render("%scLevel{console}", "x", Level.ERROR, "m"));
        assertEquals("FATAL", render("%scLevel", "x", Level.FATAL, "m"));
        assertEquals("DEBUG", render("%scLevel", "x", Level.DEBUG, "m"));
        assertEquals("TRACE", render("%scLevel", "x", Level.TRACE, "m"));
        assertEquals("NOTIC", render("%scLevel", "x", Level.forName("NOTICE", 350), "m"));
        assertEquals("X    ", render("%scLevel", "x", Level.forName("X", 360), "m"));
    }

    @Test
    void scHighlightAndScDimArePlainOffATerminal() {
        assertEquals("boom", render("%scHighlight{%msg}", "x", Level.ERROR, "boom"));
        assertEquals("boom", render("%scHighlight{%msg}", "x", Level.WARN, "boom"));
        assertEquals("fine", render("%scHighlight{%msg}", "x", Level.INFO, "fine"));
        assertEquals("12:00:00", render("%scDim{12:00:00}", "x", Level.INFO, "m"));
    }

    @Test
    void ansiIfTerminalStripsEscapesWhenNotATerminal() {
        assertEquals("SourbyCraft / Version", render("%ansiIfTerminal{%msg}", "SourbyCraft", Level.INFO, COLOURED));
        assertEquals("plain", render("%ansiIfTerminal{%msg}", "SourbyCraft", Level.INFO, "plain"));
    }

    @Test
    void theConsoleLineIsTimeLevelTagMessage() throws IOException {
        final String line = render(pattern("TerminalConsole"), "dev.iyanz.aurora.engine.X", Level.INFO, "Autosave every 5m");
        assertTrue(line.matches("(?s)\\d{2}:\\d{2}:\\d{2} INFO  Aurora       Autosave every 5m\\R"), line);
        final String warn = render(pattern("TerminalConsole"), "", Level.WARN, "**** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!");
        assertTrue(warn.matches("(?s)\\d{2}:\\d{2}:\\d{2} WARN  Minecraft    \\*\\*\\*\\* SERVER IS RUNNING IN OFFLINE/INSECURE MODE!\\R"), warn);
        final String plugin = render(pattern("TerminalConsole"), "SourbyCraft-Test-Plugin", Level.INFO, "SOURBY_METRICS_ONLOAD_OK state=WARMING");
        assertTrue(plugin.contains(" INFO  SourbyCraft-Test-Plugin SOURBY_METRICS_ONLOAD_OK state=WARMING"), plugin);
    }

    @Test
    void theFileLineKeepsTheShapeLogAnalysersParse() throws IOException {
        final String line = render(pattern("File"), "dev.iyanz.aurora.engine.X", Level.INFO, "Autosave every 5m");
        assertTrue(line.matches("(?s)\\[\\d{2}:\\d{2}:\\d{2}\\] \\[Server thread/INFO\\]: \\[Aurora\\] Autosave every 5m\\R"), line);
        final String vanilla = render(pattern("File"), "net.minecraft.server.dedicated.DedicatedServer", Level.INFO, "Done (1.000s)! For help, type \"help\"");
        assertTrue(vanilla.contains("]: [Minecraft] Done (1.000s)! For help, type \"help\""), vanilla);
    }

    @Test
    void fileAndConsolePatternsPrintNoQualifiedNameAndNoEscapes() throws IOException {
        final String xml = config();
        assertFalse(xml.contains("%style"), "%style writes escapes even when stdout is not a terminal");
        assertFalse(xml.contains("[%logger]"), "[%logger] prints fully qualified logger names");
        assertFalse(xml.contains("%highlightError"), "the console palette is %scHighlight, level-aware and terminal-only");
        assertFalse(xml.contains("LoggerNamePatternSelector"), "one pattern per appender; %scLogger decides the tag");
        for (final String appender : new String[] {"TerminalConsole", "File"}) {
            final String pattern = pattern(appender);
            assertTrue(pattern.contains("%scLogger"), appender + ": " + pattern);
            for (final String logger : new String[] {
                "ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry",
                "oshi.software.os.mac.MacOperatingSystem", "dev.iyanz.sourbycraft.Foo",
                "dev.iyanz.aurora.engine.Bar", "io.papermc.paper.threadedregions.Baz", "SourbyCraft", "Aurora", ""}) {
                final String line = render(pattern, logger, Level.INFO, COLOURED);
                assertFalse(line.contains(ESC), appender + " wrote an escape: " + line);
                assertFalse(line.matches("(?s).*\\b(dev\\.iyanz|dev\\.yanianz|ca\\.spottedleaf|io\\.papermc|net\\.minecraft|oshi)\\..*"),
                    appender + " wrote a qualified logger name: " + line);
                assertTrue(line.contains("SourbyCraft / Version"), line);
            }
        }
    }

    @Test
    void configuredRepeatFiltersCoverTheKnownStartupDuplicates() throws IOException {
        final String xml = config();
        assertTrue(xml.contains("<RepeatedMessageFilter logger=\"oshi.\" message=\"Unable to parse version\" level=\"WARN\""), xml);
        // "Tick" (a logger-name prefix: the scheduler class logs by simple name through
        // LogUtils.getClassLogger()) rather than the class name: feature patches must not name
        // region-scheduler internals (scripts/independence_policy.py SCHEDULER_SYMBOL).
        assertTrue(xml.contains("<RepeatedMessageFilter logger=\"Tick\" message=\"Region tick threads: \" level=\"INFO\""), xml);
        assertFalse(xml.matches("(?s).*\\b(TickRegions|TickRegionScheduler|RegionizedServer|ThreadedRegionizer)\\b.*"),
            "log4j2.xml (paper feature 0016) names a region-scheduler symbol");
    }

    private static Filter repeatFilter(final String logger, final String message) throws Exception {
        return repeatFilter(logger, message, "WARN");
    }

    private static Filter repeatFilter(final String logger, final String message, final String level) throws Exception {
        final PluginManager manager = new PluginManager(Node.CATEGORY);
        manager.collectPlugins();
        final PluginType<?> type = manager.getPluginType("repeatedmessagefilter");
        assertNotNull(type, "RepeatedMessageFilter is not registered in Log4j2Plugins.dat");
        final Method factory = type.getPluginClass().getMethod("createFilter", String.class, String.class, String.class);
        return (Filter) factory.invoke(null, logger, message, level);
    }

    @Test
    void theRegionTickThreadsFilterMatchesTheSchedulerClassLogger() throws Exception {
        // The scheduler class logs by simple name; a boot prints the line at configuration load
        // and again when the scheduler starts (seen on the 2026-10-22 test boot with a non-matching prefix).
        final Filter filter = repeatFilter("Tick", "Region tick threads: ", "INFO");
        final String logger = "TickRegions";
        final String line = "Region tick threads: 8 (aurora.cpu.cores unset; using all 8 available processor(s))";
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(logger, Level.INFO, line)));
        assertEquals(Filter.Result.DENY, filter.filter(event(logger, Level.INFO, line)));
    }

    /** Walks up from the working directory to the tree that holds the materialized sources, or null. */
    private static java.nio.file.Path materializedRoot() {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null) {
            if (java.nio.file.Files.isDirectory(dir.resolve("paper-server/src/main/java"))
                && java.nio.file.Files.isDirectory(dir.resolve("sourbycraft-server/src/minecraft/java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    @Test
    void everyInternalClassThatLogsByItsSimpleNameFoldsOntoPaperOrAurora() throws IOException {
        // Mojang's LogUtils.getClassLogger() names the logger after the simple class name, which
        // the converter cannot tell from a plugin's name; it folds a fixed table instead. This
        // scans the materialized Paper, Minecraft and engine sources so the table cannot go stale
        // silently after a paperRef bump.
        final java.nio.file.Path root = materializedRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "materialized sources not present (applyAllPatches)");
        final List<String> unfolded = new ArrayList<>();
        for (final String dir : new String[] {"paper-server/src/main/java", "sourbycraft-server/src/minecraft/java",
            "sourbycraft-server/src/main/java/dev/iyanz/aurora"}) {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root.resolve(dir))) {
                for (final java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    if (!java.nio.file.Files.readString(file).contains("LogUtils.getClassLogger()")) {
                        continue;
                    }
                    final String simple = file.getFileName().toString().replace(".java", "");
                    final String tag = render("%scLogger", simple, Level.INFO, "m");
                    if (!tag.equals("Paper") && !tag.equals("Aurora")) {
                        unfolded.add(simple + " -> " + tag);
                    }
                }
            }
        }
        assertTrue(unfolded.isEmpty(), "add to SourbyCraftLoggerConverter.ENGINE_CLASSES or PAPER_CLASSES: " + unfolded);
    }

    @Test
    void repeatedMessageFilterPassesTheFirstCopyOnly() throws Exception {
        final Filter filter = repeatFilter("oshi.", "Unable to parse version");
        final String oshi = "oshi.software.os.mac.MacOperatingSystem";
        final String line = "Unable to parse version 27.0 to a codename.";
        final List<Filter.Result> results = new ArrayList<>();
        results.add(filter.filter(event(oshi, Level.WARN, line)));
        results.add(filter.filter(event(oshi, Level.WARN, line)));
        results.add(filter.filter(event(oshi, Level.WARN, "Unable to parse version 28.0 to a codename.")));
        results.add(filter.filter(event(oshi, Level.WARN, "Something else")));
        results.add(filter.filter(event("other.Logger", Level.WARN, line)));
        results.add(filter.filter(event("other.Logger", Level.WARN, line)));
        assertEquals(List.of(Filter.Result.NEUTRAL, Filter.Result.DENY, Filter.Result.NEUTRAL,
            Filter.Result.NEUTRAL, Filter.Result.NEUTRAL, Filter.Result.NEUTRAL), results);
    }

    @Test
    void repeatedMessageFilterNeverDropsAThrowableOrGrowsWithoutBound() throws Exception {
        final Filter filter = repeatFilter("X", "m", "INFO");
        final LogEvent withThrown = Log4jLogEvent.newBuilder().setLoggerName("X").setLevel(Level.ERROR)
            .setMessage(new SimpleMessage("m")).setThrown(new IllegalStateException()).build();
        assertEquals(Filter.Result.NEUTRAL, filter.filter(withThrown));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(withThrown));
        for (int i = 0; i < 200; i++) {
            filter.filter(event("X", Level.INFO, "m" + i));
        }
        // Past the remembered-key budget a new line is passed every time.
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("X", Level.INFO, "m199")));
        assertEquals(Filter.Result.DENY, filter.filter(event("X", Level.INFO, "m0")));
    }

    @Test
    void repeatedMessageFilterOnlyActsAtItsLevelAndNeverOnErrors() throws Exception {
        final Filter filter = repeatFilter("oshi.", "Unable", "WARN");
        final String oshi = "oshi.software.os.mac.MacOperatingSystem";
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(oshi, Level.WARN, "Unable x")));
        // The same text as an ERROR or FATAL is a different event: always printed.
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(oshi, Level.ERROR, "Unable x")));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(oshi, Level.FATAL, "Unable x")));
        // Another level than configured is not considered at all.
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(oshi, Level.INFO, "Unable x")));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(oshi, Level.INFO, "Unable x")));
        assertEquals(Filter.Result.DENY, filter.filter(event(oshi, Level.WARN, "Unable x")));
    }

    @Test
    void repeatedMessageFilterRejectsAnErrorLevelConfiguration() {
        final Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> repeatFilter("x", "y", "ERROR"));
        assertTrue(String.valueOf(thrown.getCause()).contains("ERROR"), String.valueOf(thrown.getCause()));
    }

    @Test
    void repeatedMessageFilterNeverDropsAVeryLongLine() throws Exception {
        final Filter filter = repeatFilter("X", "m", "INFO");
        final String longLine = "m" + "x".repeat(5000);
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("X", Level.INFO, longLine)));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("X", Level.INFO, longLine)));
    }

    @Test
    void repeatedMessageFilterLetsExactlyOneConcurrentCopyThrough() throws Exception {
        for (int round = 0; round < 20; round++) {
            final Filter filter = repeatFilter("X", "m", "INFO");
            final int threads = 8;
            final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
            final java.util.concurrent.atomic.AtomicInteger passed = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        if (filter.filter(event("X", Level.INFO, "m same")) == Filter.Result.NEUTRAL) {
                            passed.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (final java.util.concurrent.Future<?> f : futures) {
                    f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, passed.get(), "round " + round);
        }
    }

    @Test
    void exceptionTextIsPlainAndPrintedOnceInBothPatterns() throws IOException {
        final IllegalStateException failure = new IllegalStateException(ESC + "[31mred failure" + ESC + "[0m");
        for (final String appender : new String[] {"TerminalConsole", "File"}) {
            final PatternLayout layout = PatternLayout.newBuilder()
                .withPattern(pattern(appender)).withConfiguration(new DefaultConfiguration()).build();
            final String line = layout.toSerializable(Log4jLogEvent.newBuilder().setLoggerName("SourbyCraft")
                .setLevel(Level.ERROR).setThreadName("Server thread").setMessage(new SimpleMessage(COLOURED))
                .setThrown(failure).build());
            assertFalse(line.contains(ESC), appender + " wrote an escape: " + line);
            assertTrue(line.contains("red failure"), line);
            assertEquals(1, line.split("java.lang.IllegalStateException", -1).length - 1,
                appender + " must print the stack trace exactly once: " + line);
        }
    }
}
