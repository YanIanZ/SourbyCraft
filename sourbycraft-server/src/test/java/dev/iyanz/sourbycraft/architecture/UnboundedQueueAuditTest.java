package dev.iyanz.sourbycraft.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * "No unbounded queue", the T10 gate item, as a test rather than an audit that goes stale.
 *
 * <p>An unbounded queue does not fail where it is written. It fails hours later, under the load
 * the qualification soak exists to apply, as a heap that will not come down — and by then the
 * line that created it looks innocent. This fails at the line instead.</p>
 *
 * <p>What is checked is admission, not size: a queue is bounded when something refuses work
 * once it is full. A capacity argument does that, and so does a semaphore in front of an
 * unbounded executor, which is why {@code BoundedIoExecutor} is listed rather than rewritten.</p>
 */
public class UnboundedQueueAuditTest {

    /**
     * Constructs that accept work without limit.
     *
     * <p>{@code newFixedThreadPool} and {@code newSingleThreadExecutor} are here because their
     * queue is an unbounded {@code LinkedBlockingQueue}: the thread count is capped and the
     * backlog is not, which is the failure that looks bounded in review.</p>
     */
    private static final Pattern UNBOUNDED = Pattern.compile(
        "newCachedThreadPool|newFixedThreadPool|newSingleThreadExecutor|newScheduledThreadPool"
            + "|newThreadPerTaskExecutor"
            + "|new\\s+ConcurrentLinkedQueue|new\\s+ConcurrentLinkedDeque"
            + "|new\\s+(?:LinkedBlockingQueue|LinkedBlockingDeque|PriorityBlockingQueue)"
            + "\\s*<[^>]*>\\s*\\(\\s*\\)");

    /** Each entry states what refuses work instead of a capacity argument. */
    private static final Map<String, String> ADMISSION_BOUNDED = Map.of(
        "dev/iyanz/sourbycraft/util/BoundedIoExecutor.java",
        "virtual threads with no queue at all: a Semaphore refuses past maximumTasks, so work is "
            + "rejected rather than accumulated");

    private static Path sourceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            final Path candidate = dir.resolve("sourbycraft-server/src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            if (Files.isDirectory(dir.resolve("src/main/java/dev/iyanz/sourbycraft"))) {
                return dir.resolve("src/main/java");
            }
        }
        throw new IllegalStateException("could not locate sourbycraft-server/src/main/java");
    }

    @Test
    void nothingAcceptsWorkWithoutABound() throws IOException {
        final Path root = sourceRoot();
        final List<String> findings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final String relative = root.relativize(file).toString().replace('\\', '/');
                if (ADMISSION_BOUNDED.containsKey(relative)) {
                    continue;
                }
                final Matcher matcher = UNBOUNDED.matcher(Files.readString(file));
                while (matcher.find()) {
                    findings.add(relative + ": " + matcher.group());
                }
            }
        }
        if (!findings.isEmpty()) {
            fail("These accept work without a bound. Give the queue a capacity, or put admission "
                + "control in front of it and list the file in ADMISSION_BOUNDED with what "
                + "refuses the work (transition §16, \"no unbounded queue\"):\n  "
                + String.join("\n  ", findings));
        }
    }

    @Test
    void theAsyncPathQueueStillDeclaresACapacity() throws IOException {
        // The one bounded queue in Aurora's source, pinned by value rather than by shape: the
        // pattern above cannot tell new LinkedBlockingQueue<>(1024) from a capacity someone
        // widened to Integer.MAX_VALUE while meaning "unbounded, but quietly".
        final String source = Files.readString(
            sourceRoot().resolve("dev/iyanz/sourbycraft/perf/AsyncPathProcessor.java"));
        final Matcher capacity = Pattern.compile(
            "new\\s+LinkedBlockingQueue\\s*<[^>]*>\\s*\\(\\s*(\\d+)\\s*\\)").matcher(source);

        assertTrue(capacity.find(), "the async path pool must construct a queue with a capacity");
        final int bound = Integer.parseInt(capacity.group(1));
        assertTrue(bound > 0 && bound <= 65_536,
            "a capacity of " + bound + " is a bound in name only");
    }

    @Test
    void everyAdmissionBoundedEntryStillNeedsIts() throws IOException {
        // Same discipline as the dependency ledger: an exception that outlives its reason
        // silently widens what the audit permits.
        final Path root = sourceRoot();
        for (final String file : ADMISSION_BOUNDED.keySet()) {
            final Path path = root.resolve(file);
            assertTrue(Files.isRegularFile(path), file + " is listed but no longer exists");
            assertTrue(UNBOUNDED.matcher(Files.readString(path)).find(),
                file + " no longer uses an unbounded construct; drop its entry");
        }
    }
}
