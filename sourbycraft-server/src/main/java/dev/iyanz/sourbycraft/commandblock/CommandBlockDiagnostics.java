package dev.iyanz.sourbycraft.commandblock;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe sink for command-block output that arrives after the command's source closed or on
 * another region's thread (a routed command's late result, usually a failure). The block's own
 * last-output field cannot take it safely, so it goes to the server log instead: once per command
 * block and command, so a repeating block cannot flood the log. Keys are bounded; past the bound
 * the sink only counts.
 */
public final class CommandBlockDiagnostics {

    static final int MAX_KEYS = 4096;

    private static volatile CommandBlockDiagnostics global = new CommandBlockDiagnostics(
        line -> dev.iyanz.sourbycraft.util.SourbyLogger.warn(line));

    private final Consumer<String> sink;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final AtomicLong suppressed = new AtomicLong();

    CommandBlockDiagnostics(final Consumer<String> sink) {
        this.sink = sink;
    }

    /** The server-wide sink. */
    public static CommandBlockDiagnostics global() {
        return global;
    }

    /**
     * Reports one deferred message.
     *
     * @param where the command block (dimension and position) or minecart (UUID)
     * @param command the command text
     * @param message the message the command produced
     * @return whether it was logged (first time for this block and command)
     */
    public boolean report(final String where, final String command, final String message) {
        final String key = where + '\u0000' + command;
        if (this.seen.size() >= MAX_KEYS && !this.seen.contains(key)) {
            this.suppressed.incrementAndGet();
            return false;
        }
        if (!this.seen.add(key)) {
            this.suppressed.incrementAndGet();
            return false;
        }
        this.sink.accept("Command block at " + where + " running '" + command + "': deferred output: " + message
            + " (logged once per command block and command)");
        return true;
    }

    /** Messages not logged because their block and command were already reported. */
    public long suppressed() {
        return this.suppressed.get();
    }
}
