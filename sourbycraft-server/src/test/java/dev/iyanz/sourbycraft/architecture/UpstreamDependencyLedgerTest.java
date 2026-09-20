package dev.iyanz.sourbycraft.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The dependency ledger, enforced.
 *
 * <p>{@code docs/architecture/dependency-ledger.md} says Aurora's own source reaches into
 * upstream internals in four files and into Canvas in one. Written down, that claim decays: the
 * next file to import a Folia scheduler internal does so in a review nobody connects to a
 * document. Here it fails a test instead.</p>
 *
 * <p>This deliberately does not forbid upstream <i>public</i> API. Bukkit, the Paper plugin
 * lifecycle and the published {@code threadedregions.scheduler} types are what the product is
 * built on; depending on them is not a leak. What is pinned is the set of files allowed to know
 * how upstream implements region ownership underneath Aurora's contracts.</p>
 */
public class UpstreamDependencyLedgerTest {

    /**
     * Upstream internals: implementation, not published API.
     *
     * <p>{@code io.papermc.paper.threadedregions.scheduler} is excluded by the negative lookahead
     * because that package is Paper's public scheduler API. The parent package is not.</p>
     */
    private static final Pattern INTERNAL = Pattern.compile(
        "io\\.canvasmc\\.[A-Za-z0-9_.]+"
            + "|io\\.papermc\\.paper\\.threadedregions\\.(?!scheduler\\.)[A-Za-z0-9_.]+"
            + "|ca\\.spottedleaf\\.[A-Za-z0-9_.]+");

    /** Every file permitted to touch the above, as §1.2 and §1.3 of the ledger list them. */
    private static final Set<String> LEDGERED = Set.of(
        "dev/iyanz/sourbycraft/execution/region/FoliaRegionBackend.java",
        "dev/iyanz/sourbycraft/execution/RegionOwnerHandoff.java",
        "dev/iyanz/sourbycraft/perf/RegionTickMetrics.java",
        "dev/iyanz/sourbycraft/perf/RegionTickMetricsHolder.java",
        "dev/iyanz/sourbycraft/config/upstream/CanvasConfigBridge.java");

    private static final String LEDGER = "docs/architecture/dependency-ledger.md";

    /** Gradle's working directory varies by invocation, so find the root rather than assume it. */
    private static Path sourceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            final Path candidate = dir.resolve("sourbycraft-server/src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            // Already inside the module.
            final Path inner = dir.resolve("src/main/java/dev/iyanz/sourbycraft");
            if (Files.isDirectory(inner)) {
                return dir.resolve("src/main/java");
            }
        }
        throw new IllegalStateException("could not locate sourbycraft-server/src/main/java from "
            + Path.of("").toAbsolutePath());
    }

    private record Leak(String file, Set<String> symbols) {}

    private static List<Leak> leaks(final Path root) throws IOException {
        final List<Leak> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final String relative = root.relativize(file).toString().replace('\\', '/');
                final Matcher matcher = INTERNAL.matcher(Files.readString(file));
                final Set<String> symbols = new TreeSet<>();
                while (matcher.find()) {
                    symbols.add(matcher.group());
                }
                if (!symbols.isEmpty()) {
                    found.add(new Leak(relative, symbols));
                }
            }
        }
        return found;
    }

    @Test
    void onlyLedgeredFilesReachIntoUpstreamInternals() throws IOException {
        final List<Leak> unlisted = leaks(sourceRoot()).stream()
            .filter(leak -> !LEDGERED.contains(leak.file()))
            .toList();

        if (!unlisted.isEmpty()) {
            final StringBuilder message = new StringBuilder(
                "These files reach into Canvas/Folia internals but are not in the dependency "
                    + "ledger.\nEither route the call through an Aurora contract, or add the file "
                    + "to " + LEDGER + " (§1.2/§1.3) and to LEDGERED here with a reason:\n");
            for (final Leak leak : unlisted) {
                message.append("  ").append(leak.file()).append('\n');
                for (final String symbol : leak.symbols()) {
                    message.append("      ").append(symbol).append('\n');
                }
            }
            fail(message.toString());
        }
    }

    @Test
    void everyLedgeredFileStillEarnsItsPlace() throws IOException {
        // A ledger that keeps entries after the dependency is gone overstates what is left to do,
        // and the next reader trusts it. An entry that no longer leaks should be deleted.
        final Path root = sourceRoot();
        final Set<String> leaking = new TreeSet<>();
        for (final Leak leak : leaks(root)) {
            leaking.add(leak.file());
        }
        final Set<String> stale = new TreeSet<>(LEDGERED);
        stale.removeAll(leaking);

        if (!stale.isEmpty()) {
            fail("These files are ledgered as depending on upstream internals but no longer do. "
                + "Remove them from " + LEDGER + " and from LEDGERED here: " + stale);
        }
    }

    @Test
    void theLedgerDocumentExists() {
        // The failure messages above send a reader to this file; it has to be there.
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(LEDGER))) {
                return;
            }
        }
        fail(LEDGER + " is missing; the ledger test points every failure at it");
    }

    @Test
    void publicUpstreamApiIsNotTreatedAsALeak() {
        // Guards the negative lookahead: if this pattern ever starts matching the published
        // scheduler API, the ledger would demand entries for ordinary, supported API use.
        assertTrue(INTERNAL.matcher(
            "io.papermc.paper.threadedregions.scheduler.EntityScheduler").find() == false,
            "the published scheduler API is a contract, not a leak");
        assertTrue(INTERNAL.matcher(
            "io.papermc.paper.threadedregions.RegionizedServer").find(),
            "but the internals beside it are");
    }

    // --- T6: dependency direction ---------------------------------------------------------

    /**
     * Files whose upstream coupling is a patch seam rather than an adapter.
     *
     * <p>Transition §12 allows this explicitly: "except in direct NMS/upstream patches where
     * indirection would be harmful and the dependency is intentionally documented". Patch 0013
     * makes upstream's {@code TickData} import these classes, so the dependency actually runs
     * upstream → Aurora; they name {@code TickTime} and {@code TickReportData} because those are
     * the contract the patched call site passes and expects back. Hiding that behind an
     * interface would mean duplicating upstream's tick-time value types to gain nothing.</p>
     */
    private static final Map<String, String> PATCH_SEAM = Map.of(
        "dev/iyanz/sourbycraft/perf/RegionTickMetrics.java",
        "patch 0013: upstream TickData calls in; TickTime/TickReportData are the contract",
        "dev/iyanz/sourbycraft/perf/RegionTickMetricsHolder.java",
        "patch 0013: generation ownership for the same patched call site");

    private static final Pattern IMPLEMENTS =
        Pattern.compile("\\bclass\\s+\\w+\\s+implements\\s+([\\w.]+)");

    @Test
    void everyUpstreamDependencyIsBehindAnAuroraInterfaceOrADocumentedPatchSeam()
            throws IOException {
        // T6's direction rule: compat implementation -> upstream, never Aurora core -> upstream.
        // A file that reaches into internals is either an adapter behind an Aurora interface, or
        // one of the patch seams above. Anything else is the accidental coupling T6 removes.
        final Path root = sourceRoot();
        final List<String> offenders = new ArrayList<>();
        for (final Leak leak : leaks(root)) {
            if (PATCH_SEAM.containsKey(leak.file())) {
                continue;
            }
            final Matcher implemented =
                IMPLEMENTS.matcher(Files.readString(root.resolve(leak.file())));
            if (!implemented.find()) {
                offenders.add(leak.file() + " (implements nothing, and is not a listed patch seam)");
            }
        }
        if (!offenders.isEmpty()) {
            fail("Upstream internals must be reached through an Aurora interface so the core can "
                + "be reasoned about without reading upstream service classes (transition §12). "
                + "These do neither:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    void canvasIsReachableFromExactlyOnePlace() throws IOException {
        // "core runtime can be reasoned about without reading Canvas service classes" is the T6
        // gate's last line. It holds only while Canvas has one doorway.
        final List<String> touching = leaks(sourceRoot()).stream()
            .filter(leak -> leak.symbols().stream().anyMatch(s -> s.startsWith("io.canvasmc.")))
            .map(Leak::file)
            .toList();

        assertEquals(List.of("dev/iyanz/sourbycraft/config/upstream/CanvasConfigBridge.java"),
            touching, "Canvas should be reachable only through the upstream config bridge");
    }

    @Test
    void aPatchSeamThatStoppedTouchingUpstreamIsNotStillExcused() throws IOException {
        // The same both-ways discipline as the ledger itself: an excuse outliving its reason
        // quietly widens what the direction test permits.
        final Set<String> leaking = new TreeSet<>();
        for (final Leak leak : leaks(sourceRoot())) {
            leaking.add(leak.file());
        }
        final Set<String> stale = new TreeSet<>(PATCH_SEAM.keySet());
        stale.removeAll(leaking);
        assertTrue(stale.isEmpty(), "these no longer touch upstream; drop the exception: " + stale);
    }
}
