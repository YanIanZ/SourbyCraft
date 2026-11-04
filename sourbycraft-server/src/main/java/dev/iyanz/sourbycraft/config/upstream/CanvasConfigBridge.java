package dev.iyanz.sourbycraft.config.upstream;

import java.util.ArrayList;
import java.util.List;

/**
 * Reloads Aurora's region configuration: {@code config/aurora-server.yml} and {@code config/aurora-worlds.yml}
 * (named canvas-*.yml before Build 49), then each loaded world's {@code canvas-patch.yml} over the world defaults.
 * The two engine files are rewritten only when the load adds or removes a key; a missing file is created. Blocking
 * file I/O on the calling thread.
 */
public final class CanvasConfigBridge implements UpstreamConfigBridge {

    @Override
    public String name() {
        return "Aurora region configuration";
    }

    @Override
    public List<String> reload() {
        final List<String> failures = new ArrayList<>(2);
        try {
            dev.iyanz.aurora.engine.GlobalConfiguration.reload();
        } catch (final Throwable failure) {
            failures.add("GlobalConfiguration.reload() failed: " + describe(failure));
        }
        try {
            dev.iyanz.aurora.engine.WorldConfig.reload();
        } catch (final Throwable failure) {
            failures.add("WorldConfig.reload() failed: " + describe(failure));
        }
        return List.copyOf(failures);
    }

    private static String describe(final Throwable failure) {
        final String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }
}
