package dev.iyanz.sourbycraft.entity.teleport;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import java.util.Optional;

/**
 * The plugin whose code is on the current stack, for attributing a teleport fallback. Walks the
 * stack only on the (logged) fallback path, never on a synchronous teleport.
 */
final class CallerPlugin {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private CallerPlugin() {}

    /** The innermost plugin frame's plugin name, or {@code null} when no plugin class is on the stack. */
    static String find() {
        final Optional<String> name = WALKER.walk(frames -> frames
            .map(frame -> nameOf(frame.getDeclaringClass().getClassLoader()))
            .filter(n -> n != null)
            .findFirst());
        return name.orElse(null);
    }

    static String nameOf(final ClassLoader loader) {
        if (loader instanceof ConfiguredPluginClassLoader plugin && plugin.getConfiguration() != null) {
            return plugin.getConfiguration().getName();
        }
        return null;
    }
}
