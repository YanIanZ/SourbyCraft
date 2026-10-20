package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkLoadTask;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The chunk system's log lines for an AWF read failure (MoonriseRegionFileIO, ChunkLoadTask) are
 * dropped by a configuration-wide log4j2 filter when the failure is a repeat AWF already counted,
 * and only then. Drives a real, private log4j2 {@link LoggerContext} through the same
 * {@code error(String, Throwable)} call those classes make.
 */
class RepeatedReadFailureLogFilterTest {

    private static final String MOONRISE_IO = MoonriseRegionFileIO.class.getName();
    private static final String CHUNK_LOAD = ChunkLoadTask.class.getName();

    private LoggerContext context;
    private final List<String> records = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RepeatedReadFailureLogFilter.uninstall();
        this.context = new LoggerContext("awf-filter-test");
        this.context.start(new DefaultConfiguration());
        capture(this.context.getConfiguration());
    }

    @AfterEach
    void tearDown() {
        RepeatedReadFailureLogFilter.uninstall();
        this.context.stop();
    }

    private void capture(final Configuration configuration) {
        final AbstractAppender appender = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(final LogEvent event) {
                RepeatedReadFailureLogFilterTest.this.records.add(event.getLoggerName() + " " + event.getLevel() + " "
                    + event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        configuration.addAppender(appender);
        final LoggerConfig root = configuration.getRootLogger();
        root.addAppender(appender, Level.ALL, null);
        root.setLevel(Level.ALL);
        this.context.updateLoggers(configuration);
    }

    private static AwfRegionStorage.RepeatedReadFailure repeat() throws Exception {
        final Constructor<AwfRegionStorage.RepeatedReadFailure> c =
            AwfRegionStorage.RepeatedReadFailure.class.getDeclaredConstructor(String.class);
        c.setAccessible(true);
        return c.newInstance("AWF could not read chunk 1,2 of w/region (1 attempt(s)): Connection refused");
    }

    private static IOException firstOfWindow() {
        return new IOException("AWF could not read chunk 1,1 of w/region (3 attempt(s)): Connection refused",
            new ConnectException("Connection refused"));
    }

    private void error(final String logger, final String message, final Throwable thrown) {
        this.context.getLogger(logger).error(message, thrown);
    }

    @Test
    void theChunkSystemLoggersAreInScope() {
        assertTrue(RepeatedReadFailureLogFilter.inScope(MOONRISE_IO), MOONRISE_IO);
        assertTrue(RepeatedReadFailureLogFilter.inScope(CHUNK_LOAD), CHUNK_LOAD);
        assertFalse(RepeatedReadFailureLogFilter.inScope(ReadFailureLog.class.getName()));
        assertFalse(RepeatedReadFailureLogFilter.inScope("SourbyCraft"), "SourbyLogger's logger");
        assertFalse(RepeatedReadFailureLogFilter.inScope(null));
    }

    @Test
    void repeatedFailuresOfTheChunkSystemAreDroppedAndEverythingElseKept() throws Exception {
        RepeatedReadFailureLogFilter.install(this.context);
        error(MOONRISE_IO, "Failed to read chunk data for task: A", repeat());
        error(CHUNK_LOAD, "Failed to load chunk data for task: A, chunk data will be lost", repeat());
        error(CHUNK_LOAD, "Failed to load entity data for task: B", new CompletionException(repeat()));
        assertEquals(List.of(), this.records, "repeats, also wrapped, are dropped");

        error(MOONRISE_IO, "Failed to read chunk data for task: first", firstOfWindow());
        error(CHUNK_LOAD, "Failed to load chunk data for task: other", new IOException("region file truncated"));
        error("dev.iyanz.sourbycraft.Other", "outside the scope", repeat());
        error("SourbyCraft", "ours too", repeat());
        this.context.getLogger(MOONRISE_IO).warn("no throwable");
        this.context.getLogger(MOONRISE_IO).info("Failed {}", "with a parameter", new IllegalStateException("x"));
        assertEquals(List.of(
            MOONRISE_IO + " ERROR Failed to read chunk data for task: first",
            CHUNK_LOAD + " ERROR Failed to load chunk data for task: other",
            "dev.iyanz.sourbycraft.Other ERROR outside the scope",
            "SourbyCraft ERROR ours too",
            MOONRISE_IO + " WARN no throwable",
            MOONRISE_IO + " INFO Failed with a parameter"), this.records);
    }

    @Test
    void withoutTheFilterTheChunkSystemLogsEveryRepeat() throws Exception {
        error(MOONRISE_IO, "Failed to read chunk data for task: A", repeat());
        assertEquals(1, this.records.size(), "baseline: log4j2 logs the repeat");
    }

    @Test
    void theFilterSurvivesAReconfiguration() throws Exception {
        RepeatedReadFailureLogFilter.install(this.context);
        final Configuration next = new DefaultConfiguration();
        this.context.setConfiguration(next);
        assertTrue(RepeatedReadFailureLogFilter.contains(this.context.getConfiguration().getFilter()),
            "added again to the new configuration");
        assertTrue(RepeatedReadFailureLogFilter.instance().isStarted(),
            "the old configuration's stop() stopped the shared instance; it is started again");
        capture(this.context.getConfiguration());
        error(CHUNK_LOAD, "Failed to load chunk data for task: A", repeat());
        error(CHUNK_LOAD, "Failed to load chunk data for task: first", firstOfWindow());
        assertEquals(List.of(CHUNK_LOAD + " ERROR Failed to load chunk data for task: first"), this.records);
    }

    @Test
    void installingTwiceAddsTheFilterOnce() {
        RepeatedReadFailureLogFilter.install(this.context);
        RepeatedReadFailureLogFilter.install(this.context);
        assertTrue(this.context.getConfiguration().getFilter() == RepeatedReadFailureLogFilter.instance(),
            "one filter, not a composite of two");
        RepeatedReadFailureLogFilter.uninstall();
        assertFalse(RepeatedReadFailureLogFilter.contains(this.context.getConfiguration().getFilter()));
    }

    @Test
    void aThrowableCarriedByTheMessageIsDropped() throws Exception {
        RepeatedReadFailureLogFilter.install(this.context);
        this.context.getLogger(CHUNK_LOAD).error(new ParameterizedMessage("x {}", new Object[] {"a", repeat()}));
        assertEquals(List.of(), this.records, "Message.getThrowable() is a repeat");
        // the pre-event check (log4j2 asks before it builds an event) must already see it
        final Message carrying = new ParameterizedMessage("x {}", new Object[] {"a", repeat()});
        assertEquals(Filter.Result.DENY, RepeatedReadFailureLogFilter.instance()
            .filter(this.context.getLogger(CHUNK_LOAD), Level.ERROR, null, carrying, null));

        this.context.getLogger(CHUNK_LOAD).error(new ParameterizedMessage("x {}", new Object[] {"a", firstOfWindow()}));
        this.context.getLogger(CHUNK_LOAD).error((Message) new SimpleMessage("plain"));
        assertEquals(List.of(CHUNK_LOAD + " ERROR x a", CHUNK_LOAD + " ERROR plain"), this.records);
    }

    @Test
    void aRepeatPassedAsTheTrailingPositionalParameterIsDropped() throws Exception {
        RepeatedReadFailureLogFilter.install(this.context);
        this.context.getLogger(CHUNK_LOAD).error("x {}", "a", repeat());
        assertEquals(List.of(), this.records, "p0..pN overloads reach the varargs check");

        this.context.getLogger(CHUNK_LOAD).error("x {}", "a", firstOfWindow());
        assertEquals(List.of(CHUNK_LOAD + " ERROR x a"), this.records);
    }

    @Test
    void theFilterJoinsAnExistingConfigurationFilterAsAComposite() throws Exception {
        final Configuration configuration = this.context.getConfiguration();
        final ThresholdFilter existing = ThresholdFilter.createFilter(Level.INFO, Filter.Result.NEUTRAL, Filter.Result.DENY);
        ((org.apache.logging.log4j.core.filter.Filterable) configuration).addFilter(existing);
        RepeatedReadFailureLogFilter.install(this.context);
        RepeatedReadFailureLogFilter.install(this.context);

        final CompositeFilter composite = (CompositeFilter) configuration.getFilter();
        assertEquals(List.of(existing, RepeatedReadFailureLogFilter.instance()), List.of(composite.getFiltersArray()),
            "both filters, ours once");
        assertTrue(RepeatedReadFailureLogFilter.contains(composite));

        error(CHUNK_LOAD, "Failed to load chunk data for task: A", repeat());
        this.context.getLogger(CHUNK_LOAD).debug("below the existing filter's threshold");
        error(CHUNK_LOAD, "Failed to load chunk data for task: first", firstOfWindow());
        assertEquals(List.of(CHUNK_LOAD + " ERROR Failed to load chunk data for task: first"), this.records,
            "the repeat is dropped, the existing filter still applies");

        RepeatedReadFailureLogFilter.uninstall();
        assertFalse(RepeatedReadFailureLogFilter.contains(configuration.getFilter()));
        assertTrue(configuration.getFilter() == existing, "only ours is removed");
    }
}
