package dev.iyanz.sourbycraft.console;

import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Lets a known repeated startup line through once per process and drops later identical copies.
 *
 * <p>Configured in {@code log4j2.xml} (paper feature patch 0016) for lines that third-party or
 * upstream code prints more than once during startup with the same text: OSHI's
 * {@code Unable to parse version 27.0 to a codename.} (WARN, once per {@code SystemInfo} user) and
 * the region scheduler's {@code Region tick threads: N (...)} (INFO, at configuration load and again
 * when the scheduler starts).
 *
 * <p>An event is considered only when its logger name starts with {@code logger}, its level is
 * exactly {@code level}, its formatted message starts with {@code message}, it carries no throwable
 * and its text is at most {@value #MAX_KEY_LENGTH} characters. ERROR and FATAL are never considered
 * (and cannot be configured), so the same text at another level, or as an error, is always printed.
 * A considered event is NEUTRAL the first time its exact logger name and text are seen and DENY
 * afterwards. At most {@value #MAX_KEYS} distinct lines are remembered; once full, new lines pass.
 * The remember-or-drop decision is synchronized, so concurrent copies let exactly one through.
 */
@Plugin(name = "RepeatedMessageFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
public final class RepeatedMessageFilter extends AbstractFilter {

    static final int MAX_KEYS = 64;
    static final int MAX_KEY_LENGTH = 1024;

    private final String logger;
    private final String message;
    private final Level level;
    private final Set<String> seen = new HashSet<>();

    private RepeatedMessageFilter(final String logger, final String message, final Level level) {
        super(Result.NEUTRAL, Result.NEUTRAL);
        this.logger = logger;
        this.message = message;
        this.level = level;
    }

    @PluginFactory
    public static RepeatedMessageFilter createFilter(
        @PluginAttribute("logger") final String logger,
        @PluginAttribute("message") final String message,
        @PluginAttribute("level") final String level
    ) {
        if (logger == null || logger.isEmpty() || message == null || message.isEmpty() || level == null) {
            throw new IllegalArgumentException("RepeatedMessageFilter needs logger, message and level attributes");
        }
        final Level parsed = Level.toLevel(level, null);
        if (parsed == null || parsed.isMoreSpecificThan(Level.ERROR)) {
            throw new IllegalArgumentException("RepeatedMessageFilter level must be WARN or lower, not " + level
                + " (ERROR and FATAL are always printed)");
        }
        return new RepeatedMessageFilter(logger, message, parsed);
    }

    @Override
    public Result filter(final LogEvent event) {
        final String name = event.getLoggerName();
        if (event.getLevel() != this.level || name == null || !name.startsWith(this.logger)
            || event.getThrown() != null || event.getMessage() == null) {
            return Result.NEUTRAL;
        }
        final String text = event.getMessage().getFormattedMessage();
        if (text == null || text.length() > MAX_KEY_LENGTH || !text.startsWith(this.message)) {
            return Result.NEUTRAL;
        }
        final String key = name + '\n' + text;
        synchronized (this.seen) {
            if (this.seen.contains(key)) {
                return Result.DENY;
            }
            if (this.seen.size() < MAX_KEYS) {
                this.seen.add(key);
            }
        }
        return Result.NEUTRAL;
    }

    @Override
    public String toString() {
        return "RepeatedMessageFilter[logger=" + this.logger + ", message=" + this.message + ", level=" + this.level + "]";
    }
}
