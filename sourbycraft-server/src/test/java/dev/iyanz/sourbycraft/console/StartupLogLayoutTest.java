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
 * The shipped {@code log4j2.xml} and the SourbyCraft log4j plugins it uses ({@code %scLogger},
 * {@code %ansiIfTerminal}, {@code RepeatedMessageFilter}). The plugins live in the
 * {@code log4jPlugins} source set, which is on the test runtime classpath only, so they are reached
 * the way log4j reaches them: through patterns and the plugin registry.
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

    /** The defaultPattern of the appender named {@code appender}, XML-unescaped. */
    private static String defaultPattern(final String appender) throws IOException {
        final String xml = config();
        final int at = xml.indexOf("name=\"" + appender + "\"");
        assertTrue(at >= 0, appender + " appender missing");
        final Matcher m = Pattern.compile("defaultPattern=\"([^\"]*)\"").matcher(xml);
        assertTrue(m.find(at), appender + " has no LoggerNamePatternSelector defaultPattern");
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
    void scLoggerShortensQualifiedNamesAndKeepsShortOnes() {
        final String[][] cases = {
            {"ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry", "[DataConverter]"},
            {"oshi.software.os.mac.MacOperatingSystem", "[OSHI]"},
            {"dev.iyanz.sourbycraft.perf.AsyncPathProcessor", "[SourbyCraft/AsyncPathProcessor]"},
            {"dev.iyanz.aurora.engine.GlobalConfiguration", "[Aurora/GlobalConfiguration]"},
            {"io.canvasmc.canvas.Config", "[Aurora/Config]"},
            {"io.papermc.paper.threadedregions.TickRegionScheduler$Inner", "[TickRegionScheduler]"},
            {"org.bukkit.craftbukkit.CraftServer", "[CraftServer]"},
            {"ca.spottedleaf.moonrise.common.util.MoonriseCommon", "[MoonriseCommon]"},
            // Unknown names are a plugin's or library's identity: never shortened.
            {"com.example.plugin.Main", "[com.example.plugin.Main]"},
            {"My.Plugin", "[My.Plugin]"},
            {"dev.iyanzfake.Other", "[dev.iyanzfake.Other]"},
            {"SourbyCraft", "[SourbyCraft]"},
            {"Aurora", "[Aurora]"},
            {"TickRegions", "[TickRegions]"},
            {"SourbyCraft/AWF", "[SourbyCraft/AWF]"},
            {"", ""},
        };
        for (final String[] c : cases) {
            assertEquals(c[1], render("%scLogger", c[0], Level.INFO, "m"), c[0]);
            // Not a terminal in tests: the console option must not colour either.
            assertEquals(c[1], render("%scLogger{console}", c[0], Level.INFO, "m"), c[0]);
        }
    }

    @Test
    void ansiIfTerminalStripsEscapesWhenNotATerminal() {
        assertEquals("SourbyCraft / Version", render("%ansiIfTerminal{%msg}", "SourbyCraft", Level.INFO, COLOURED));
        assertEquals("plain", render("%ansiIfTerminal{%msg}", "SourbyCraft", Level.INFO, "plain"));
    }

    @Test
    void fileAndConsolePatternsPrintNoQualifiedNameAndNoEscapes() throws IOException {
        final String xml = config();
        assertFalse(xml.contains("%style"), "%style writes escapes even when stdout is not a terminal");
        assertFalse(xml.contains("[%logger]"), "[%logger] prints fully qualified logger names");
        for (final String appender : new String[] {"TerminalConsole", "File"}) {
            final String pattern = defaultPattern(appender);
            assertTrue(pattern.contains("%scLogger"), appender + ": " + pattern);
            for (final String logger : new String[] {
                "ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry",
                "oshi.software.os.mac.MacOperatingSystem", "dev.iyanz.sourbycraft.Foo",
                "io.canvasmc.canvas.Bar", "SourbyCraft", "Aurora"}) {
                final String line = render(pattern, logger, Level.INFO, COLOURED);
                assertFalse(line.contains(ESC), appender + " wrote an escape: " + line);
                assertFalse(line.matches("(?s).*\\b(dev\\.iyanz|io\\.canvasmc|ca\\.spottedleaf|oshi)\\..*"),
                    appender + " wrote a qualified logger name: " + line);
                assertTrue(line.contains("SourbyCraft / Version"), line);
            }
        }
    }

    @Test
    void configuredRepeatFiltersCoverTheKnownStartupDuplicates() throws IOException {
        final String xml = config();
        assertTrue(xml.contains("<RepeatedMessageFilter logger=\"oshi.\" message=\"Unable to parse version\" level=\"WARN\""), xml);
        // "Tick" (a logger-name prefix) rather than the scheduler class name: feature patches must not
        // name region-scheduler internals (scripts/independence_policy.py SCHEDULER_SYMBOL).
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
                .withPattern(defaultPattern(appender)).withConfiguration(new DefaultConfiguration()).build();
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
