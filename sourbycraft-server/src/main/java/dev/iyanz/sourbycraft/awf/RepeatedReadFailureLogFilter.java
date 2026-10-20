package dev.iyanz.sourbycraft.awf;

import java.beans.PropertyChangeListener;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.message.Message;

/**
 * A configuration-wide log4j2 filter that drops the chunk system's log record of a
 * {@link AwfRegionStorage.RepeatedReadFailure}: an AWF read failure after the first of its
 * {@link ReadFailureLog} window, which AWF already counted and summarises. Without it one chunk load
 * during a store outage logs two ERRORs per position (MoonriseRegionFileIO "Failed to read chunk
 * data", ChunkLoadTask "Failed to load chunk/entity data"), ~1460 for a single {@code forceload}.
 *
 * <p>Scope: only events whose throwable, or a cause of it, is a {@code RepeatedReadFailure}, logged
 * by a logger that is not SourbyCraft's own ({@link #inScope}). Every other event is {@code NEUTRAL}
 * (the configuration decides as before); the first failure of a window is a plain
 * {@code IOException} and logs in full. Only the log record is dropped: the caller handles the
 * throwable exactly as before. Events without a throwable cost one null check.
 *
 * <p>Why "not ours" rather than the chunk-system logger names: a {@code RepeatedReadFailure} is
 * thrown only by {@link AwfRegionStorage}'s load-path read, whose callers are the chunk system's
 * reads (live run: every such record came from MoonriseRegionFileIO or ChunkLoadTask), so the two
 * scopes drop the same records; naming the upstream packages here would make this class an upstream
 * dependency the architecture ledger (UpstreamDependencyLedgerTest) does not allow without an Aurora
 * interface. The test pins that both chunk-system loggers are in scope.
 *
 * <p>Installed on the first repeated failure ({@link #ensureInstalled()}, called by
 * {@link AwfRegionStorage}), so a server whose AWF never fails never touches its log configuration.
 * A listener on the {@link LoggerContext} adds it again to every new configuration (log4j2
 * reconfigure, {@code monitorInterval} reload). Without log4j-core as the backend it does nothing.
 */
public final class RepeatedReadFailureLogFilter extends AbstractFilter {

    /** SourbyCraft's own loggers (its classes, and {@code SourbyLogger}'s "SourbyCraft"): never filtered. */
    private static final String OWN_PREFIX = "dev.iyanz.";
    private static final String OWN_LOGGER = "SourbyCraft";

    private static final int MAX_CAUSE_DEPTH = 8;
    private static final RepeatedReadFailureLogFilter INSTANCE = new RepeatedReadFailureLogFilter();
    private static final Object LOCK = new Object();
    private static volatile LoggerContext installedOn;
    private static PropertyChangeListener listener;

    private final AtomicLong dropped = new AtomicLong();

    private RepeatedReadFailureLogFilter() {
        super(Result.DENY, Result.NEUTRAL);
        start();
    }

    /** The filter instance (one per JVM). */
    public static RepeatedReadFailureLogFilter instance() {
        return INSTANCE;
    }

    /** Log records dropped so far (pre-event checks; a dropped record is never built as an event). */
    public long dropped() {
        return this.dropped.get();
    }

    /** Installs the filter on the server's log4j2 context once; cheap after the first call. */
    public static void ensureInstalled() {
        if (installedOn != null) return;
        final Object context = LogManager.getContext(false);
        if (context instanceof LoggerContext core) install(core);
    }

    /**
     * Adds the filter to {@code context}'s current configuration and to every configuration it
     * switches to later. Idempotent per context.
     */
    public static void install(final LoggerContext context) {
        synchronized (LOCK) {
            if (installedOn == context) return;
            if (installedOn != null) uninstallLocked();
            final PropertyChangeListener reconfigured = event -> {
                if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                    && event.getNewValue() instanceof Configuration next) {
                    addTo(context, next);
                }
            };
            context.addPropertyChangeListener(reconfigured);
            listener = reconfigured;
            installedOn = context;
            addTo(context, context.getConfiguration());
        }
    }

    /** Removes the filter and its listener again. Tests only. */
    static void uninstall() {
        synchronized (LOCK) {
            uninstallLocked();
        }
    }

    private static void uninstallLocked() {
        final LoggerContext context = installedOn;
        if (context == null) return;
        context.removePropertyChangeListener(listener);
        final Configuration configuration = context.getConfiguration();
        if (configuration instanceof org.apache.logging.log4j.core.filter.Filterable filterable && contains(configuration.getFilter())) {
            filterable.removeFilter(INSTANCE);
            context.updateLoggers();
        }
        listener = null;
        installedOn = null;
    }

    private static void addTo(final LoggerContext context, final Configuration configuration) {
        if (contains(configuration.getFilter())) return;
        if (!(configuration instanceof org.apache.logging.log4j.core.filter.Filterable filterable)) return;
        // belt and braces: stop(long, TimeUnit) below already keeps the shared instance started
        if (!INSTANCE.isStarted()) INSTANCE.start();
        filterable.addFilter(INSTANCE);
        context.updateLoggers(configuration);
    }

    /** True when {@code filter} is this filter or a composite holding it. */
    static boolean contains(final Filter filter) {
        if (filter == INSTANCE) return true;
        if (filter instanceof CompositeFilter composite) {
            for (final Filter each : composite.getFiltersArray()) {
                if (each == INSTANCE) return true;
            }
        }
        return false;
    }

    private Result decide(final String loggerName, final Throwable thrown) {
        if (thrown == null || !inScope(loggerName)) return Result.NEUTRAL;
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AwfRegionStorage.RepeatedReadFailure) {
                this.dropped.incrementAndGet();
                return Result.DENY;
            }
            current = current.getCause();
        }
        return Result.NEUTRAL;
    }

    /** Whether records of {@code loggerName} may be dropped: any logger but SourbyCraft's own. */
    static boolean inScope(final String loggerName) {
        return loggerName != null && !loggerName.startsWith(OWN_PREFIX) && !loggerName.equals(OWN_LOGGER);
    }

    private static String name(final Logger logger) {
        return logger == null ? null : logger.getName();
    }

    /**
     * The shared instance outlives every configuration, but {@code LoggerContext.setConfiguration}
     * stops the previous one after the listener has added this filter to the next, which would stop
     * it: stay started.
     */
    @Override
    public boolean stop(final long timeout, final TimeUnit timeUnit) {
        return true;
    }

    @Override
    public Result filter(final LogEvent event) {
        return decide(event.getLoggerName(), event.getThrown());
    }

    @Override
    public Result filter(final Logger logger, final Level level, final Marker marker, final Object msg, final Throwable t) {
        return t == null ? Result.NEUTRAL : decide(name(logger), t);
    }

    @Override
    public Result filter(final Logger logger, final Level level, final Marker marker, final Message msg, final Throwable t) {
        final Throwable thrown = t != null ? t : msg == null ? null : msg.getThrowable();
        return thrown == null ? Result.NEUTRAL : decide(name(logger), thrown);
    }

    @Override
    public Result filter(final Logger logger, final Level level, final Marker marker, final String msg, final Object... params) {
        if (params == null || params.length == 0 || !(params[params.length - 1] instanceof Throwable t)) return Result.NEUTRAL;
        return decide(name(logger), t);
    }
}
