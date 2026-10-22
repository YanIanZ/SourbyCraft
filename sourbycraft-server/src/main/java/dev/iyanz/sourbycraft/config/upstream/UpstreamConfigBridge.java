package dev.iyanz.sourbycraft.config.upstream;

import java.util.List;

/**
 * Re-reading the configuration of whatever engine implementation is underneath.
 *
 * <p>SourbyCraft folds the engine's own reload into {@code /sourbycraft reload} so an operator has
 * one command. That is a compatibility obligation, not an architectural one, and this interface is
 * where it is met: nothing above it names the implementation, so replacing the implementation is a
 * new bridge rather than an edit to the reload path.</p>
 */
public interface UpstreamConfigBridge {

    /** What a bridge is re-reading, for the operator-facing log line. */
    String name();

    /**
     * Re-reads the underlying engine's configuration.
     *
     * <p>Implementations report failures rather than throwing: a configuration file the engine
     * cannot re-read must not abort the rest of a reload, and the previous values stay in force.</p>
     *
     * @return one message per part that failed; empty when everything re-read
     */
    List<String> reload();
}
