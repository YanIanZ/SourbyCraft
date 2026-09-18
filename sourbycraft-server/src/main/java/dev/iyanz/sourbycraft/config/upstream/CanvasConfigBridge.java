package dev.iyanz.sourbycraft.config.upstream;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link UpstreamConfigBridge} over the Canvas configuration classes.
 *
 * <p>The only place SourbyCraft names Canvas. Runs the same
 * {@code GlobalConfiguration.reload()} and {@code WorldConfig.reload()} that the removed
 * {@code /canvas reload} ran.</p>
 *
 * <p>Each part is isolated: a file the engine cannot re-read leaves its previous values in force
 * and does not stop the other part, or the SourbyCraft reload around it. Options the engine cached
 * at construction update the config object but take effect on the next restart, which matches
 * Canvas's own contract that some options cannot change at runtime.</p>
 */
public final class CanvasConfigBridge implements UpstreamConfigBridge {

    @Override
    public String name() {
        return "Canvas";
    }

    @Override
    public List<String> reload() {
        final List<String> failures = new ArrayList<>(2);
        try {
            io.canvasmc.canvas.GlobalConfiguration.reload();
        } catch (final Throwable failure) {
            failures.add("GlobalConfiguration.reload() failed: " + describe(failure));
        }
        try {
            io.canvasmc.canvas.WorldConfig.reload();
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
