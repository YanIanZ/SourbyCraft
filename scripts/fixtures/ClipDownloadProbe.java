import dev.iyanz.sourbyclip.DownloadContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Run against the assembled bootstrap jar; no remote downloads or server boot. */
class ClipDownloadProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        byte[] payload = new byte[(9 << 20) + 137]; // crosses the 8 MiB transfer boundary
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        Path source = root.resolve("source.bin");
        Files.write(source, payload);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
        DownloadContext context = new DownloadContext(hash, source.toUri().toURL(), "fixture.jar");
        Path output = root.resolve("output");
        context.download(output);
        require(Arrays.equals(payload, Files.readAllBytes(context.getOutputFile(output))), "complete transfer");
        Files.write(context.getOutputFile(output), new byte[]{1, 2, 3});
        context.download(output);
        require(Arrays.equals(payload, Files.readAllBytes(context.getOutputFile(output))), "corrupt cache recovery");
        try {
            new DownloadContext(new byte[32], source.toUri().toURL(), "wrong-hash.jar").download(output);
            throw new AssertionError("Invalid hash accepted");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("Hash check failed"), "hash failure diagnostic");
        }
        Files.delete(source);
        context.download(output); // source unavailable; verified cache must suffice
        System.out.println("PASS: multi-chunk transfer, corrupt-cache recovery, hash rejection, offline cache reuse");
    }
    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
