package dev.iyanz.sourbycraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.brand.AuroraBoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code SourbyCraftBootstrap}'s stage accounting: the boot bar's denominator is a hand-kept
 * constant, and the COMPLETE/DEGRADED/FAILED verdict is derived from it.
 */
public class SourbyCraftBootstrapTest {

    private static final Pattern STAGE_CALL = Pattern.compile("^\\s*stage\\(\"", Pattern.MULTILINE);
    private static final Pattern TOTAL = Pattern.compile("static final int TOTAL_STAGES\\s*=\\s*(\\d+)\\s*;");

    private static String source() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            final String tail = "src/main/java/dev/iyanz/sourbycraft/core/SourbyCraftBootstrap.java";
            for (final Path candidate : new Path[] {
                dir.resolve("sourbycraft-server").resolve(tail), dir.resolve(tail)}) {
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
        }
        throw new IllegalStateException("could not locate SourbyCraftBootstrap.java");
    }

    @Test
    void totalStagesEqualsTheNumberOfStageCalls() throws IOException {
        final String source = source();
        final Matcher total = TOTAL.matcher(source);
        assertTrue(total.find(), "TOTAL_STAGES declaration not found");
        int calls = 0;
        final Matcher matcher = STAGE_CALL.matcher(source);
        while (matcher.find()) {
            calls++;
        }
        assertTrue(calls > 0, "no stage(\"...\") calls found; the pattern is stale");
        assertEquals(Integer.parseInt(total.group(1)), calls,
            "TOTAL_STAGES is the boot bar's denominator and the verdict's basis: update it with the stage() calls");
    }

    @Test
    void bootstrapAppliesTheConfiguredMotd() throws IOException {
        // messages.motd is seeded as "applied at startup"; the only reader is SourbyMotd, so the boot
        // sequence must call it (init() itself needs a server, hence the source check).
        assertTrue(source().contains("SourbyMotd.apply("),
            "SourbyCraftBootstrap no longer applies messages.motd; the seeded key would be dead again");
    }

    @Test
    void verdictIsRunningDegradedOrFailed() {
        assertEquals(AuroraRuntime.State.RUNNING, SourbyCraftBootstrap.verdict(0, 14));
        assertEquals(AuroraRuntime.State.DEGRADED, SourbyCraftBootstrap.verdict(1, 14));
        assertEquals(AuroraRuntime.State.DEGRADED, SourbyCraftBootstrap.verdict(13, 14));
        assertEquals(AuroraRuntime.State.FAILED, SourbyCraftBootstrap.verdict(14, 14));
    }

    @Test
    void aThrowingStageIsCountedRecordedAndStepPast() throws Exception {
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting()) {
            final boolean[] ranAfter = {false};
            SourbyCraftBootstrap.stage("ok first", () -> {});
            SourbyCraftBootstrap.stage("explodes", () -> {
                throw new IllegalStateException("boom");
            });
            SourbyCraftBootstrap.stage("still runs", () -> ranAfter[0] = true);

            assertTrue(ranAfter[0], "a failing stage must not skip the stages after it");
            assertEquals(1, SourbyCraftBootstrap.failureCountForTesting());
            assertEquals(AuroraRuntime.State.DEGRADED,
                SourbyCraftBootstrap.verdict(SourbyCraftBootstrap.failureCountForTesting(), 14));

            final List<AuroraBoot.Stage> stages = SourbyCraftBootstrap.publishBootStagesForTesting();
            assertEquals(stages, SourbyCraftBootstrap.bootStages());
            assertEquals(List.of("ok first", "explodes", "still runs"),
                stages.stream().map(AuroraBoot.Stage::name).toList());
            assertEquals(List.of(true, false, true),
                stages.stream().map(AuroraBoot.Stage::successful).toList());
        }
        assertTrue(SourbyCraftBootstrap.bootStages().isEmpty(), "the reset closer restores an empty state");
    }

    @Test
    void eachStagePrintsOneLineOnTheAuroraLogger() throws Exception {
        final java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        final java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(final java.util.logging.LogRecord record) {
                if (java.util.logging.Logger.getLogger("Aurora").isLoggable(record.getLevel())) {
                    records.add(record);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        final java.util.logging.Logger aurora = java.util.logging.Logger.getLogger("Aurora");
        aurora.addHandler(handler);
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting()) {
            SourbyCraftBootstrap.stage("first", () -> {});
            SourbyCraftBootstrap.stage("second", () -> {});
        } finally {
            aurora.removeHandler(handler);
        }
        final List<String> lines = records.stream().map(java.util.logging.LogRecord::getMessage).toList();
        assertEquals(2, lines.size(), "one visible line per finished stage: " + lines);
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("START")), lines.toString());
        assertTrue(lines.get(0).contains("OK") && lines.get(0).contains("first"), lines.get(0));
        assertTrue(lines.get(1).contains("second"), lines.get(1));
    }

    /** Captures log4j events of the logger {@code name} at {@code level} and above until closed. */
    private static AutoCloseable captureLog4j(final String name, final org.apache.logging.log4j.Level level,
                                              final List<org.apache.logging.log4j.core.LogEvent> into) {
        final org.apache.logging.log4j.core.LoggerContext context =
            (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        final org.apache.logging.log4j.core.config.Configuration config = context.getConfiguration();
        final org.apache.logging.log4j.core.appender.AbstractAppender appender =
            new org.apache.logging.log4j.core.appender.AbstractAppender("capture-" + name, null, null, true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                @Override public void append(final org.apache.logging.log4j.core.LogEvent event) {
                    into.add(event.toImmutable());
                }
            };
        appender.start();
        final org.apache.logging.log4j.core.config.LoggerConfig logger =
            new org.apache.logging.log4j.core.config.LoggerConfig(name, level, true);
        logger.addAppender(appender, level, null);
        config.addLogger(name, logger);
        context.updateLoggers();
        return () -> {
            config.removeLogger(name);
            context.updateLoggers();
            appender.stop();
        };
    }

    @Test
    void stageStartIsARealDebugEvent() throws Exception {
        final List<org.apache.logging.log4j.core.LogEvent> events = new java.util.ArrayList<>();
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting();
             AutoCloseable capture = captureLog4j("Aurora", org.apache.logging.log4j.Level.DEBUG, events)) {
            SourbyCraftBootstrap.stage("traced", () -> {});
        }
        final List<org.apache.logging.log4j.core.LogEvent> starts = events.stream()
            .filter(e -> e.getMessage().getFormattedMessage().startsWith("START")).toList();
        assertEquals(1, starts.size(), events.toString());
        assertEquals(org.apache.logging.log4j.Level.DEBUG, starts.get(0).getLevel(),
            "JUL FINE reaches log4j as TRACE; START must be DEBUG so <Logger name=\"Aurora\" level=\"debug\"/> shows it");
        assertTrue(starts.get(0).getMessage().getFormattedMessage().contains("traced"));
    }

    @Test
    void aStageThatRunsLongGetsAStillRunningWarning() throws Exception {
        final java.util.concurrent.CountDownLatch warned = new java.util.concurrent.CountDownLatch(1);
        final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        final boolean[] sawWarningWhileRunning = {false};
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting();
             AutoCloseable slow = SourbyCraftBootstrap.slowStageWarningForTesting(1L, message -> {
                 warnings.add(message);
                 warned.countDown();
             })) {
            SourbyCraftBootstrap.stage("hangs a while", () -> {
                try {
                    sawWarningWhileRunning[0] = warned.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(sawWarningWhileRunning[0], "no warning while the stage was still running");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("hangs a while") && warnings.get(0).contains("still running"), warnings.get(0));
    }

    @Test
    void aFastStageGetsNoStillRunningWarning() throws Exception {
        final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting();
             AutoCloseable slow = SourbyCraftBootstrap.slowStageWarningForTesting(60_000L, warnings::add)) {
            SourbyCraftBootstrap.stage("quick", () -> {});
        }
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void bootstrapLogsUnderTheShortEngineTag() throws IOException {
        assertFalse(source().contains("\"Aurora Engine\""),
            "the engine logs as [Aurora], like its configuration; [Aurora Engine] was a second tag for the same thing");
    }

    @Test
    void aCleanRunRecordsNoFailure() throws Exception {
        try (AutoCloseable reset = SourbyCraftBootstrap.resetStagesForTesting()) {
            SourbyCraftBootstrap.stage("a", () -> {});
            SourbyCraftBootstrap.stage("b", () -> {});
            assertEquals(0, SourbyCraftBootstrap.failureCountForTesting());
            assertTrue(SourbyCraftBootstrap.publishBootStagesForTesting().stream()
                .allMatch(AuroraBoot.Stage::successful));
            assertFalse(SourbyCraftBootstrap.bootStages().isEmpty());
        }
    }
}
