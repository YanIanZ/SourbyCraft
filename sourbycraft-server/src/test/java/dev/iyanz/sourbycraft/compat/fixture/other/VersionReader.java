package dev.iyanz.sourbycraft.compat.fixture.other;

import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/** A plugin class that reads the server version with the same regex but is not PaperLib's Environment. */
public class VersionReader {

    public final boolean matched;

    public VersionReader() {
        this.matched = Pattern.compile("\\(MC: (\\d)\\.(\\d+)\\.?(\\d+?)?(?: Pre-Release )?(\\d)?\\)").matcher(Bukkit.getVersion()).find();
    }
}
