package dev.iyanz.sourbycraft.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deployment-environment classifier for the Auto-CDS layer.
 *
 * <p>The one job of this class is to answer a single question for {@link SourbyBootstrap}:
 * <em>is it safe and beneficial to fork a second JVM here, or should we boot inline and
 * just print the single-JVM CDS flag hint?</em> Forking a child JVM (to drive
 * {@code -XX:+AutoCreateSharedArchive}) is a win only on true bare metal with headroom.
 * Everywhere a memory cap or a process manager is in play, forking is actively harmful:
 * <ul>
 *   <li><b>Double-committed heap.</b> Under a cgroup / container memory limit with
 *       {@code -Xms == -Xmx}, the orchestrator JVM and the forked server each commit the
 *       full heap → the kernel OOM-kills the container.</li>
 *   <li><b>Hidden PID.</b> A service manager (systemd), panel daemon (Wings), or
 *       orchestrator (k8s, Nomad) tracks the launched PID for memory graphs and stop
 *       signals. Forking hides the real server behind the wrapper PID, so
 *       {@code systemctl stop} / panel Stop / graceful termination hit the wrong process.</li>
 * </ul>
 *
 * <p>Detection is an <b>ordered list of detectors</b> ({@link #DETECTORS}); the first that
 * matches wins. Adding a new scenario is one list entry — no growing {@code if} ladder.
 * Each detector returns a labeled {@link Detection} carrying the {@link ForkPolicy}.
 *
 * <p><b>JDK-only.</b> Runs before any external library is on the classpath, so it touches
 * only {@code java.*} + {@code /proc} + {@code /sys} + environment variables.
 */
final class CdsEnvironment {

    private CdsEnvironment() {}

    /** Whether forking a helper JVM is acceptable in a detected environment. */
    enum ForkPolicy {
        /** Managed / capped / short-lived: never fork — boot inline and hint the flag. */
        INLINE,
        /** Real VM or bare metal with headroom: forking is acceptable (subject to -Xms check). */
        FORK_OK
    }

    /** Coarse family of the detected environment, for messaging + future policy tuning. */
    enum EnvKind {
        BARE_METAL,
        PANEL,             // Pterodactyl / Pelican / generic game panel
        CONTAINER,         // Docker / Podman / LXC / OpenVZ / containerd / generic
        ORCHESTRATOR,      // Kubernetes / Nomad
        SERVICE_MANAGER,   // systemd
        CI,                // CI / build runners
        WSL,               // Windows Subsystem for Linux
        CLOUD_VM,          // AWS / GCP / Azure IaaS (uncapped)
        CGROUP_CAPPED      // cgroup v1/v2 hard memory limit, family otherwise unknown
    }

    /** A positive environment classification: human label + kind + fork policy. */
    record Detection(String label, EnvKind kind, ForkPolicy policy) {
        boolean canFork() { return policy == ForkPolicy.FORK_OK; }
    }

    /**
     * A single, cheap, side-effect-free environment probe. Returns a populated
     * {@link Optional} when it recognizes the environment, else {@link Optional#empty()}.
     */
    @FunctionalInterface
    interface EnvDetector {
        Optional<Detection> detect();
    }

    // ------------------------------------------------------------------
    // Detector chain — ordered by specificity. First match wins.
    //
    // Ordering rationale:
    //   1. Panels first: they set the most specific vars and are the highest-value
    //      case to label precisely (operator-facing hint mentions the panel).
    //   2. Explicit container/orchestrator markers next.
    //   3. Service manager (systemd) — owns the PID even on bare metal.
    //   4. CI — short-lived, no archive benefit.
    //   5. Generic capped-cgroup catch-all (v1 + v2) — any missed container.
    //   6. WSL / cloud-VM — informational; these still permit forking.
    // ------------------------------------------------------------------
    static final List<EnvDetector> DETECTORS = List.of(
            CdsEnvironment::detectPterodactylPelican,
            CdsEnvironment::detectGamePanel,
            CdsEnvironment::detectKubernetes,
            CdsEnvironment::detectNomad,
            CdsEnvironment::detectPodman,
            CdsEnvironment::detectDocker,
            CdsEnvironment::detectContainerEnvVar,   // container=lxc|oci|systemd-nspawn|...
            CdsEnvironment::detectLxcOpenVz,
            CdsEnvironment::detectSystemd,
            CdsEnvironment::detectCi,
            CdsEnvironment::detectCgroupMemoryLimit,  // v2 then v1 — generic capped catch-all
            CdsEnvironment::detectWsl,
            CdsEnvironment::detectCloudVm
    );

    /**
     * Classifies the current environment by running the detector chain in order.
     * Returns the first match, or {@link Optional#empty()} for plain bare metal.
     * Never throws — a detector that hits an I/O error simply does not match.
     */
    static Optional<Detection> classify() {
        for (EnvDetector d : DETECTORS) {
            Optional<Detection> hit = safe(d);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /** Legacy-compatible label: the detected environment name, or {@code null} on bare metal. */
    static String label() {
        return classify().map(Detection::label).orElse(null);
    }

    private static Optional<Detection> safe(EnvDetector d) {
        try {
            Optional<Detection> r = d.detect();
            return r == null ? Optional.empty() : r;
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Detectors
    // ------------------------------------------------------------------

    /** Pterodactyl and Pelican panels. Both export {@code P_SERVER_*}; Pelican also sets {@code PELICAN}. */
    private static Optional<Detection> detectPterodactylPelican() {
        boolean ptero = env("P_SERVER_UUID") != null || env("P_SERVER_LOCATION") != null;
        boolean pelican = env("PELICAN") != null;
        if (ptero || pelican) {
            String label = pelican ? "Pelican panel" : "Pterodactyl panel";
            return hit(label, EnvKind.PANEL, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /** Generic game-server panel egg (Wings-style): {@code SERVER_MEMORY} + {@code SERVER_IP}. */
    private static Optional<Detection> detectGamePanel() {
        if (env("SERVER_MEMORY") != null && env("SERVER_IP") != null) {
            return hit("game panel", EnvKind.PANEL, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /**
     * Kubernetes. The service-account env var + injected secret dir are both present in a
     * pod; either alone is enough. Pods are cgroup-capped and the kubelet tracks the
     * container's main PID → inline.
     */
    private static Optional<Detection> detectKubernetes() {
        if (env("KUBERNETES_SERVICE_HOST") != null
                || env("KUBERNETES_PORT") != null
                || exists("/var/run/secrets/kubernetes.io")
                || exists("/run/secrets/kubernetes.io/serviceaccount")) {
            return hit("Kubernetes", EnvKind.ORCHESTRATOR, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /** HashiCorp Nomad allocation. The runner tracks the task's PID and applies resource caps. */
    private static Optional<Detection> detectNomad() {
        if (env("NOMAD_ALLOC_ID") != null || env("NOMAD_TASK_NAME") != null || env("NOMAD_JOB_NAME") != null) {
            return hit("Nomad", EnvKind.ORCHESTRATOR, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /** Podman. Rootless/rootful Podman drops a {@code /run/.containerenv} marker (and often {@code container=podman}). */
    private static Optional<Detection> detectPodman() {
        String c = env("container");
        if ((c != null && c.toLowerCase(Locale.ROOT).contains("podman"))
                || exists("/run/.containerenv") || exists("/.containerenv")) {
            return hit("Podman", EnvKind.CONTAINER, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /**
     * Docker / containerd / Moby. {@code /.dockerenv} is the canonical marker; also check the
     * cgroup path for a {@code docker}/{@code containerd}/{@code kubepods} slice. Even without an
     * explicit {@code -m} cap we inline: forking still hides the server behind the wrapper PID
     * that the daemon tracks for {@code docker stop}.
     */
    private static Optional<Detection> detectDocker() {
        if (exists("/.dockerenv")) {
            return hit("Docker", EnvKind.CONTAINER, ForkPolicy.INLINE);
        }
        String cg = readFirstLine("/proc/1/cgroup");
        if (cg != null) {
            String low = cg.toLowerCase(Locale.ROOT);
            if (low.contains("/docker") || low.contains("/containerd") || low.contains("/kubepods")
                    || low.contains("docker-") || low.contains("containerd-")) {
                return hit("container (containerd/Docker)", EnvKind.CONTAINER, ForkPolicy.INLINE);
            }
        }
        return Optional.empty();
    }

    /** Freedesktop {@code container=} contract var (systemd-nspawn, LXC, OCI runtimes set this). */
    private static Optional<Detection> detectContainerEnvVar() {
        String c = env("container");
        if (c != null && !c.isBlank()) {
            return hit("container (" + c + ")", EnvKind.CONTAINER, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /**
     * LXC / OpenVZ. When the contract env var is absent (e.g. an unprivileged LXC guest that
     * did not inherit it), fall back to filesystem markers: OpenVZ exposes {@code /proc/vz} +
     * {@code /proc/bc}; LXC leaves {@code container=lxc} in PID 1's environ and a {@code /dev/lxd}
     * socket / {@code lxc} cgroup slice.
     */
    private static Optional<Detection> detectLxcOpenVz() {
        // OpenVZ containers (not the host, which also has /proc/vz but with /proc/bc populated differently).
        if (exists("/proc/vz") && !exists("/proc/bc")) {
            return hit("OpenVZ", EnvKind.CONTAINER, ForkPolicy.INLINE);
        }
        if (exists("/dev/lxd/sock") || exists("/dev/.lxc")) {
            return hit("LXC", EnvKind.CONTAINER, ForkPolicy.INLINE);
        }
        String environ = readNulDelimited("/proc/1/environ");
        if (environ != null) {
            String low = environ.toLowerCase(Locale.ROOT);
            if (low.contains("container=lxc")) return hit("LXC", EnvKind.CONTAINER, ForkPolicy.INLINE);
            if (low.contains("container=openvz")) return hit("OpenVZ", EnvKind.CONTAINER, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /**
     * systemd service unit. Present when launched by {@code systemd} as a service:
     * {@code INVOCATION_ID} is set per-invocation, {@code MANAGERPID} points at the manager,
     * and {@code /run/systemd/system} exists when systemd is PID 1. Forking here hides the
     * real PID from {@code systemctl} — the unit's {@code MainPID} would be the wrapper, so
     * {@code systemctl stop}/{@code status} and {@code Type=notify}/{@code cgroup} accounting
     * target the wrong process. Boot inline.
     */
    private static Optional<Detection> detectSystemd() {
        if (env("INVOCATION_ID") != null || env("MANAGERPID") != null
                || env("JOURNAL_STREAM") != null || env("NOTIFY_SOCKET") != null) {
            return hit("systemd service", EnvKind.SERVICE_MANAGER, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /**
     * CI / build runners. Short-lived and ephemeral — an archive written on shutdown is thrown
     * away with the runner, so forking only adds a process and startup cost with zero payoff.
     */
    private static Optional<Detection> detectCi() {
        if (isTruthy(env("CI"))
                || env("GITHUB_ACTIONS") != null
                || env("GITLAB_CI") != null
                || env("BUILDKITE") != null
                || env("CIRCLECI") != null
                || env("JENKINS_URL") != null
                || env("TEAMCITY_VERSION") != null
                || env("TF_BUILD") != null            // Azure Pipelines
                || env("DRONE") != null
                || env("BITBUCKET_BUILD_NUMBER") != null) {
            return hit("CI runner", EnvKind.CI, ForkPolicy.INLINE);
        }
        return Optional.empty();
    }

    /**
     * Generic cgroup memory-limit catch-all for any container we did not name above. Checks
     * cgroup v2 ({@code memory.max}) then v1 ({@code memory.limit_in_bytes}), treating the
     * respective "unlimited" sentinels as no cap. A hard cap almost always means a container
     * with {@code -Xms == -Xmx}, so forking risks the double-commit OOM.
     */
    private static Optional<Detection> detectCgroupMemoryLimit() {
        // cgroup v2 unified hierarchy.
        String v2 = readFirstLine("/sys/fs/cgroup/memory.max");
        if (v2 != null && !v2.isEmpty() && !v2.equals("max")) {
            Long bytes = parseLong(v2);
            if (bytes != null && bytes > 0 && bytes < UNLIMITED_V1) {
                return hit("container (cgroup v2 memory limit)", EnvKind.CGROUP_CAPPED, ForkPolicy.INLINE);
            }
        }
        // cgroup v1 — the limit lives under the memory controller; the "unlimited" value is a
        // huge page-aligned sentinel (~PAGE_COUNTER_MAX), so anything below it is a real cap.
        for (String p : new String[]{
                "/sys/fs/cgroup/memory/memory.limit_in_bytes",
                "/sys/fs/cgroup/memory.limit_in_bytes"}) {
            String v1 = readFirstLine(p);
            if (v1 != null && !v1.isEmpty()) {
                Long bytes = parseLong(v1);
                if (bytes != null && bytes > 0 && bytes < UNLIMITED_V1) {
                    return hit("container (cgroup v1 memory limit)", EnvKind.CGROUP_CAPPED, ForkPolicy.INLINE);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Windows Subsystem for Linux. Detected via the Microsoft-tagged kernel release. WSL is a
     * developer environment with no orchestrator PID tracking and typically no hard cgroup cap
     * (WSL2 uses a global {@code .wslconfig} VM budget, already caught by the cgroup detector if
     * set), so forking is fine — but we still label it so the operator knows the environment.
     */
    private static Optional<Detection> detectWsl() {
        String osrelease = readFirstLine("/proc/sys/kernel/osrelease");
        if (osrelease != null) {
            String low = osrelease.toLowerCase(Locale.ROOT);
            if (low.contains("microsoft") || low.contains("wsl")) {
                return hit("WSL", EnvKind.WSL, ForkPolicy.FORK_OK);
            }
        }
        if (env("WSL_DISTRO_NAME") != null || env("WSL_INTEROP") != null) {
            return hit("WSL", EnvKind.WSL, ForkPolicy.FORK_OK);
        }
        return Optional.empty();
    }

    /**
     * Cloud IaaS VM (AWS EC2 / GCP GCE / Azure). A plain VM is uncapped from the JVM's point of
     * view and nobody tracks a wrapper PID, so forking is acceptable — the earlier cgroup
     * detector already caught the case where the VM additionally imposes a cap. We only probe
     * cheap, offline signals (no metadata-endpoint network calls at boot).
     */
    private static Optional<Detection> detectCloudVm() {
        // Hypervisor DMI markers are the cheapest offline signal.
        String vendor = readFirstLine("/sys/class/dmi/id/sys_vendor");
        if (vendor != null) {
            String low = vendor.toLowerCase(Locale.ROOT);
            if (low.contains("amazon")) return hit("AWS EC2", EnvKind.CLOUD_VM, ForkPolicy.FORK_OK);
            if (low.contains("google")) return hit("GCP GCE", EnvKind.CLOUD_VM, ForkPolicy.FORK_OK);
            if (low.contains("microsoft")) return hit("Azure VM", EnvKind.CLOUD_VM, ForkPolicy.FORK_OK);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Low-level JDK-only probes
    // ------------------------------------------------------------------

    /** cgroup v1 "unlimited" sentinel: PAGE_COUNTER_MAX ≈ 0x7FFFFFFFFFFFF000 on 64-bit; anything at/above ≈ no cap. */
    private static final long UNLIMITED_V1 = 0x7FFF_FFFF_FFFF_F000L;

    private static Optional<Detection> hit(String label, EnvKind kind, ForkPolicy policy) {
        return Optional.of(new Detection(label, kind, policy));
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static boolean isTruthy(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
    }

    private static boolean exists(String path) {
        try {
            return Files.exists(Paths.get(path));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reads the first line of a file, trimmed; null on any error or if not readable. */
    private static String readFirstLine(String path) {
        return readSmall(path, s -> {
            int nl = s.indexOf('\n');
            return (nl >= 0 ? s.substring(0, nl) : s).trim();
        });
    }

    /** Reads a NUL-delimited file (e.g. {@code /proc/<pid>/environ}) as space-normalized text; null on error. */
    private static String readNulDelimited(String path) {
        return readSmall(path, s -> s.replace('\0', ' '));
    }

    private static String readSmall(String path, java.util.function.Function<String, String> shape) {
        try {
            Path p = Paths.get(path);
            if (!Files.isReadable(p)) return null;
            byte[] raw = Files.readAllBytes(p);
            String s = new String(raw, StandardCharsets.UTF_8);
            return shape.apply(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
