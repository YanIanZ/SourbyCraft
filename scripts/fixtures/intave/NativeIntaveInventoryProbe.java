import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** V28: exercise the pure inventory checker compiled from the actual private Gradle script. */
public final class NativeIntaveInventoryProbe {
    private static void rejected(Path root, Set<String> sources, Set<String> fixtures) {
        try {
            NativeIntaveInventory.INSTANCE.verify(root, sources, fixtures);
            throw new AssertionError("Unrecorded or unsafe build input was accepted");
        } catch (IllegalStateException expected) {
            // Kotlin check rejects the input before compilation or packaging.
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Set<String> sources = Set.of("src/main/java/Check.java", "generated/main/java/BuildConfig.java",
            "libs/ProtocolLib.jar");
        Set<String> fixtures = Set.of("src/test/resources/pinned.ptr");
        for (String name : sources) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "recorded input");
        }
        Path fixture = root.resolve("src/test/resources/pinned.ptr");
        Files.createDirectories(fixture.getParent());
        Files.write(fixture, new byte[] {0, 1, 2});
        NativeIntaveInventory.INSTANCE.verify(root, sources, fixtures);
        for (String name : new String[] {"src/main/java/Extra.java", "src/test/java/ExtraTest.java",
            "generated/main/java/Extra.java", "src/main/resources/extra.yml",
            "libs/Extra.jar", "libs/nested/ProtocolLib.jar"}) {
            Path file = root.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "unrecorded input");
            try { rejected(root, sources, fixtures); }
            finally { Files.delete(file); }
        }
        Path source = root.resolve("src/main/java/Check.java");
        Path external = root.getParent().resolve("outside.java");
        Files.writeString(external, "recorded input");
        Files.delete(source);
        Files.createSymbolicLink(source, external);
        rejected(root, sources, fixtures);
        Files.delete(source);
        Files.writeString(source, "recorded input");
        NativeIntaveInventory.INSTANCE.verify(root, sources, fixtures);
        System.out.println("Private Gradle inventory probe passed");
    }
}
