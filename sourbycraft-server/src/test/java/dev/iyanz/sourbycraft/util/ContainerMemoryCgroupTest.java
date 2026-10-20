package dev.iyanz.sourbycraft.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iyanz.sourbycraft.util.ContainerMemory.Cgroup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cgroup resolution through {@code /proc/self/cgroup} against fixture trees (VPS verification F1,
 * docs/development/linux-vps-verification-2026-10-21.md): the host view has no memory files at the
 * mount root, only under the process's own cgroup path.
 */
class ContainerMemoryCgroupTest {

    private static final long GIB = 1L << 30;

    @TempDir
    Path tmp;

    private Cgroup cgroup(final String procSelfCgroup) throws IOException {
        final Path proc = tmp.resolve("proc-self-cgroup");
        Files.writeString(proc, procSelfCgroup);
        return new Cgroup(proc, tmp.resolve("sys-fs-cgroup"));
    }

    private void write(final String relative, final String content) throws IOException {
        final Path p = tmp.resolve("sys-fs-cgroup").resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content + "\n");
    }

    @Test
    void v2HostViewReadsTheProcessCgroupNotTheMountRoot() throws IOException {
        final Cgroup cg = cgroup("0::/system.slice/sc.scope\n");
        write("system.slice/sc.scope/memory.max", String.valueOf(8 * GIB));
        write("system.slice/sc.scope/memory.current", String.valueOf(3 * GIB));

        assertEquals(8 * GIB, ContainerMemory.detect(cg));
        assertEquals(3 * GIB, ContainerMemory.currentBytes(cg));
    }

    @Test
    void v2UnlimitedLeafUsesTheNearestFiniteAncestorAndThatAncestorsUsage() throws IOException {
        final Cgroup cg = cgroup("0::/system.slice/sc.scope\n");
        write("system.slice/sc.scope/memory.max", "max");
        write("system.slice/sc.scope/memory.current", String.valueOf(1 * GIB));
        write("system.slice/memory.max", String.valueOf(4 * GIB));
        write("system.slice/memory.current", String.valueOf(2 * GIB)); // leaf plus siblings
        write("memory.max", "max");

        assertEquals(4 * GIB, ContainerMemory.detect(cg));
        assertEquals(2 * GIB, ContainerMemory.currentBytes(cg), "usage of the cgroup that enforces the limit");
    }

    @Test
    void v2SmallerAncestorLimitWinsOverALargerLeafLimit() throws IOException {
        final Cgroup cg = cgroup("0::/a/b\n");
        write("a/b/memory.max", String.valueOf(8 * GIB));
        write("a/b/memory.current", String.valueOf(1 * GIB));
        write("a/memory.max", String.valueOf(2 * GIB));
        write("a/memory.current", String.valueOf(1 * GIB + 1));

        assertEquals(2 * GIB, ContainerMemory.detect(cg));
        assertEquals(1 * GIB + 1, ContainerMemory.currentBytes(cg));
    }

    @Test
    void containerRootViewKeepsTheFixedRootPaths() throws IOException {
        // private cgroup namespace: the process's cgroup is the mount root
        final Cgroup cg = cgroup("0::/\n");
        write("memory.max", String.valueOf(8 * GIB));
        write("memory.current", String.valueOf(5 * GIB));

        assertEquals(8 * GIB, ContainerMemory.detect(cg));
        assertEquals(5 * GIB, ContainerMemory.currentBytes(cg));
    }

    @Test
    void aProcessCgroupPathThisMountDoesNotShowFallsBackToTheRoot() throws IOException {
        // host-namespace container: /proc says /docker/<id>, the mount shows only that cgroup at its root
        final Cgroup cg = cgroup("0::/docker/abc123\n");
        write("memory.max", String.valueOf(2 * GIB));
        write("memory.current", String.valueOf(1 * GIB));

        assertEquals(2 * GIB, ContainerMemory.detect(cg));
        assertEquals(1 * GIB, ContainerMemory.currentBytes(cg));
    }

    @Test
    void v1ReadsTheMemoryControllerPath() throws IOException {
        final Cgroup cg = cgroup("12:cpu,cpuacct:/user.slice\n4:memory:/system.slice/sc.service\n1:name=systemd:/x\n");
        write("memory/system.slice/sc.service/memory.limit_in_bytes", String.valueOf(6 * GIB));
        write("memory/system.slice/sc.service/memory.usage_in_bytes", String.valueOf(2 * GIB));

        assertEquals(6 * GIB, ContainerMemory.detect(cg));
        assertEquals(2 * GIB, ContainerMemory.currentBytes(cg));
    }

    @Test
    void v1UnlimitedLeafWalksUpToAFiniteParent() throws IOException {
        final Cgroup cg = cgroup("4:memory:/system.slice/sc.service\n");
        write("memory/system.slice/sc.service/memory.limit_in_bytes", "9223372036854771712");
        write("memory/system.slice/sc.service/memory.usage_in_bytes", String.valueOf(1 * GIB));
        write("memory/system.slice/memory.limit_in_bytes", String.valueOf(3 * GIB));
        write("memory/system.slice/memory.usage_in_bytes", String.valueOf(2 * GIB));

        assertEquals(3 * GIB, ContainerMemory.detect(cg));
        assertEquals(2 * GIB, ContainerMemory.currentBytes(cg));
    }

    @Test
    void v1ContainerRootViewKeepsTheFixedRootPaths() throws IOException {
        final Cgroup cg = cgroup("4:memory:/\n");
        write("memory/memory.limit_in_bytes", String.valueOf(4 * GIB));
        write("memory/memory.usage_in_bytes", String.valueOf(1 * GIB));

        assertEquals(4 * GIB, ContainerMemory.detect(cg));
        assertEquals(1 * GIB, ContainerMemory.currentBytes(cg));
    }

    @Test
    void unlimitedEverywhereFallsBackToPhysicalMemoryButStillReportsUsage() throws IOException {
        final Cgroup cg = cgroup("0::/user.slice/s.scope\n");
        write("user.slice/s.scope/memory.max", "max");
        write("user.slice/s.scope/memory.current", String.valueOf(1 * GIB));
        write("user.slice/memory.max", "max");

        final long physical = ContainerMemory.detect(new Cgroup(tmp.resolve("absent"), tmp.resolve("absent-root")));
        assertEquals(physical, ContainerMemory.detect(cg), "no cgroup limit applies: physical memory");
        assertEquals(1 * GIB, ContainerMemory.currentBytes(cg), "leaf usage when nothing enforces a limit");
    }

    @Test
    void missingOrEscapingCgroupFilesAreUnknownNotAnException() throws IOException {
        assertEquals(-1L, ContainerMemory.currentBytes(new Cgroup(tmp.resolve("absent"), tmp.resolve("absent-root"))));
        final Cgroup escape = cgroup("0::/../../etc\n");
        write("memory.current", String.valueOf(1 * GIB));
        assertEquals(1 * GIB, ContainerMemory.currentBytes(escape), "a path outside the mount falls back to the root");
    }

    @Test
    void swapIsResolvedThroughTheProcessCgroupToo() throws IOException {
        final Cgroup cg = cgroup("0::/system.slice/sc.scope\n");
        write("system.slice/sc.scope/memory.swap.max", String.valueOf(2 * GIB));
        write("system.slice/sc.scope/memory.swap.current", String.valueOf(GIB / 2));

        assertEquals(2 * GIB, ContainerMemory.swapTotalBytes(cg));
        assertEquals(GIB / 2, ContainerMemory.swapUsedBytes(cg, 2 * GIB));
    }
}
