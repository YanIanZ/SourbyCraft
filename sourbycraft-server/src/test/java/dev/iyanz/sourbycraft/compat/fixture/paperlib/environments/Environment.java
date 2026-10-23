package dev.iyanz.sourbycraft.compat.fixture.paperlib.environments;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/**
 * Same shape as PaperLib 1.0.x {@code io.papermc.lib.environments.Environment}'s constructor (the
 * class EssentialsX relocates to {@code com.earth2me.essentials.paperlib}): the version regex
 * constant, then {@code Bukkit.getVersion()}, then the same group parsing. Written from the
 * EssentialsX 2.22.1 bytecode; no PaperLib code is copied.
 */
public class Environment {

    private final int minecraftVersion;
    private final int minecraftPatchVersion;

    public Environment() {
        final Pattern versionPattern = Pattern.compile("\\(MC: (\\d)\\.(\\d+)\\.?(\\d+?)?(?: Pre-Release )?(\\d)?\\)");
        final Matcher matcher = versionPattern.matcher(Bukkit.getVersion());
        int version = 0;
        int patch = 0;
        if (matcher.find()) {
            final MatchResult result = matcher.toMatchResult();
            try {
                version = Integer.parseInt(result.group(2), 10);
            } catch (final Exception ignored) {
            }
            if (result.groupCount() >= 3) {
                try {
                    patch = Integer.parseInt(result.group(3), 10);
                } catch (final Exception ignored) {
                }
            }
        }
        this.minecraftVersion = version;
        this.minecraftPatchVersion = patch;
    }

    public int getMinecraftVersion() {
        return this.minecraftVersion;
    }

    public int getMinecraftPatchVersion() {
        return this.minecraftPatchVersion;
    }

    /** PaperLib's gate for {@code AsyncChunksPaper_13} / {@code AsyncTeleportPaper_13}. */
    public boolean isVersion(final int minor, final int patch) {
        return this.minecraftVersion > minor || (this.minecraftVersion >= minor && this.minecraftPatchVersion >= patch);
    }
}
