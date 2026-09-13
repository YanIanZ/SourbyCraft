package dev.iyanz.sourbycraft.perf;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigSnapshotTest {
    @Test void snapshotsDoNotAliasMutableConfig() {
        final Config config = Config.inMemory();
        final var names = new ArrayList<>(List.of("hello"));
        config.set("messages.names", names);
        config.set("feature.enabled", true);
        final var snapshot = ConfigSnapshot.copyOf(config);
        names.add("changed");
        config.set("feature.enabled", false);
        assertEquals(List.of("hello"), snapshot.values().get("messages.names"));
        assertEquals(true, snapshot.values().get("feature.enabled"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> ((List<?>)snapshot.values().get("messages.names")).clear());
    }

    @Test void defaultsDoNotRewriteExistingOperatorFile(@TempDir Path directory) throws Exception {
        final Path path = directory.resolve("operator.toml");
        final String original = "# operator comment\n[viaversion]\nauto-provision = false\n";
        Files.writeString(path, original);
        try (var config = CommentedFileConfig.of(path)) {
            config.load();
            final var seed = SourbyCraftConfig.class.getDeclaredMethod("seedDefaults", CommentedFileConfig.class);
            seed.setAccessible(true);
            seed.invoke(null, config);
            assertEquals(original, Files.readString(path));
            assertEquals(false, config.get("viaversion.auto-provision"));
        }
    }
}
