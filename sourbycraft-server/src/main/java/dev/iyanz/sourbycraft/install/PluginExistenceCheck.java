package dev.iyanz.sourbycraft.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

public final class PluginExistenceCheck {

    private PluginExistenceCheck() {}

    public static boolean isInstalled(Path pluginsDir, String name) {
        if (!Files.isDirectory(pluginsDir)) return false;
        String prefix = name.toLowerCase(Locale.ROOT);
        try (Stream<Path> s = Files.list(pluginsDir)) {
            return s.anyMatch(p -> {
                String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return fn.endsWith(".jar") && fn.startsWith(prefix);
            });
        } catch (IOException e) {
            return false;
        }
    }
}
