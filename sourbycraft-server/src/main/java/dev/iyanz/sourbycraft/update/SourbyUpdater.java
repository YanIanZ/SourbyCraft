package dev.iyanz.sourbycraft.update;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

/**
 * Advanced SourbyCraft auto-updater (F1-8) — targets SourbyCraft's own GitHub releases, replacing
 * the archived upstream updater the Folia base shipped (which pointed at a now-dead feed).
 *
 * <p>Design (all knobs live in the unified operator config under {@code [updater]}, wired
 * by {@link dev.iyanz.sourbycraft.update.AutoUpdateSettings}):
 *
 * <ol>
 *   <li><b>Retarget.</b> Hits {@code https://api.github.com/repos/<owner>/<repo>/releases}
 *       for the configurable SourbyCraft repo (default {@code YanIanZ/SourbyCraft}), NOT the dead
 *       upstream feed the base shipped. Staging dir is {@code auto_update/sourbycraft}.</li>
 *   <li><b>Channel-aware.</b> Reads this build's version ({@link BuildInfo}, e.g. {@code 26.2-REL}),
 *       derives its {@link UpdateChannel}, and only considers releases whose tag is on the SAME
 *       channel. A REL server never offers a DEV/EXP build. Newer-ness is a numeric
 *       {@link SemVer} compare of the channel-stripped cores.</li>
 *   <li><b>Same Minecraft version only.</b> Releases whose tag core ({@code v26.3-r100} → 26.3)
 *       names another Minecraft version than the running build are kept apart from the rest: the
 *       newest newer one is announced as a manual upgrade but never staged or applied (audit SEC-3),
 *       and it never hides a newer release for this Minecraft version, which is selected, staged
 *       and applied on its own.</li>
 *   <li><b>Verify.</b> Asset URLs must be https (plain http only on a loopback host while the
 *       {@link UpdateOverrides} test override is set). The asset name must be a plain file name
 *       ending in {@code .jar}. The download is bounded BEFORE it is read (the asset's declared size,
 *       which may not exceed {@link #MAX_JAR_BYTES}, else {@link #MAX_JAR_BYTES}; an oversize
 *       {@code Content-Length} is refused without reading the body, a stream that runs past the bound
 *       is cut off, and the whole transfer has {@link #MAX_DOWNLOAD_TIME}). It is written to a fresh
 *       temporary file, which then must match the declared size, its SHA-256 must equal the release
 *       asset {@code digest} (mandatory), it must open as a ZIP/JAR, and — when this build ships a
 *       release public key — its detached Ed25519 signature asset must verify
 *       ({@link ReleaseSignatureVerifier}). A failing download is deleted and never staged. A staged
 *       jar that already has the release digest is reused instead of downloaded again.</li>
 *   <li><b>Launcher gate (SEC-4).</b> The authentic jar must also declare an embedded SourbyClip
 *       this server can hand over to ({@link LauncherCompatibility}: protocol 1, version at least
 *       3.0.23, manifest and {@code META-INF/sourbyclip.properties} agreeing). Anything else,
 *       including a jar with no launcher metadata, is deleted and answered with
 *       {@link Outcome#CLIP_INCOMPATIBLE}.</li>
 *   <li><b>Safe apply modes.</b> {@code notify} (default) logs + op-notifies only; {@code stage}
 *       additionally downloads+verifies and places the jar in {@code auto_update/sourbycraft} plus
 *       writes the pointer {@code auto_update/core.path}, and nothing installs it on restart: an
 *       operator runs {@code /update apply} (which reuses the verified copy) or swaps it by hand;
 *       {@code auto} also swaps the launch jar and restarts ({@link UpdateApplier}); {@code off}
 *       disables checks. The running jar is NEVER hot-swapped. When an apply of the newest release
 *       failed ({@value UpdateApplier#APPLY_FAILED_FILE}, or an older build's
 *       {@value UpdateApplier#LEGACY_PENDING_FILE}), scheduled checks only announce it until
 *       {@code /update apply} succeeds.</li>
 *   <li><b>One check at a time.</b> Scheduled checks and {@code /update} share one guard
 *       ({@link #tryCheck}); a check that finds another running is skipped, never run alongside.</li>
 *   <li><b>Rollback hold.</b> While {@link UpdateRollbackGuard} holds after a failed boot on an
 *       applied update, every check returns {@link Outcome#HELD} without touching the network.
 *       {@code applied.tag} keeps an applied release from being offered again, except when the
 *       guard confirmed that release and this server nevertheless runs an older build (the launch
 *       jar was replaced outside the updater, e.g. a container recreated from an older image).</li>
 *   <li><b>Polite polling.</b> The releases listing is a conditional request ({@code ETag} /
 *       {@code If-None-Match}; a 304 reuses the cached listing) and a {@code 429}/{@code 403} with
 *       {@code Retry-After} or an exhausted {@code X-RateLimit-Remaining} puts the updater in a
 *       backoff window during which checks return {@link Outcome#RATE_LIMITED} immediately.</li>
 *   <li><b>Folia-safe.</b> Periodic checks run on {@link Bukkit#getAsyncScheduler()} (never the
 *       Bukkit global scheduler). Network + disk I/O happen off the region threads.</li>
 * </ol>
 *
 * <p>Every network path is defensive: a 404 (no matching release yet), a rate-limit, a parse error
 * or an offline host is logged at INFO/WARN and the check simply ends — it can never crash boot or
 * a scheduled tick. All network I/O goes through {@link HttpTransport}; the package-private
 * constructor injects it (plus the build info, clock, signature verifier and rollback guard) so the
 * check path is unit-tested without GitHub.
 */
public final class SourbyUpdater {

    private static final Gson GSON = new Gson();
    /** Production releases endpoint prefix; the test fixture can redirect it ({@link UpdateOverrides}). */
    private static final String GITHUB_API = UpdateOverrides.DEFAULT_REPOS_BASE;
    private static final long DAILY_MILLIS = TimeUnit.DAYS.toMillis(1);
    /** Ceiling for a release jar download: the bound when the asset declares no size, and the largest size it may declare. */
    static final long MAX_JAR_BYTES = 512L * 1024 * 1024;
    /** Ceiling for a detached signature asset download. */
    static final long MAX_SIGNATURE_BYTES = 16L * 1024;
    /** Total time one asset download may take, on top of the transport's per-read timeout. */
    static final Duration MAX_DOWNLOAD_TIME = Duration.ofMinutes(15);
    /** A release jar asset name: a plain file name of letters, digits, '.', '_' and '-' ending in .jar. */
    private static final java.util.regex.Pattern SAFE_ASSET_NAME =
        java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar", java.util.regex.Pattern.CASE_INSENSITIVE);
    /**
     * How many newer releases one check inspects through their signed manifests (newest first)
     * before giving up, counted separately for this Minecraft version and for newer ones: bounds the
     * requests a check makes when several releases are yanked or still rolling out.
     */
    static final int MAX_MANIFEST_CANDIDATES = 5;
    /** Backoff window bounds applied to whatever the server asks for. */
    static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);
    /** Backoff when a 429 carries no usable hint at all. */
    static final Duration DEFAULT_BACKOFF = Duration.ofMinutes(5);

    private final Path autoUpdateDir;
    private final Path stageDir;
    private final Path corePathFile;
    private final Path latestPathFile;
    private final HttpTransport transport;
    private final Supplier<BuildInfo> buildInfo;
    private final Clock clock;
    private final ReleaseSignatureVerifier signatures;
    private final UpdateRollbackGuard rollbackGuard;
    private final Supplier<String> rolloutServerId;

    // Conditional-request state for the releases listing, and the rate-limit backoff window.
    // Scheduled checks and /update are serialised by checkRunning (tryCheck); the ETag pair keeps
    // its own lock for any direct check() caller (never the start()/stop() monitor: a network
    // call must not block shutdown).
    private final Object listingLock = new Object();
    private @Nullable String releasesEtag;
    private @Nullable JsonArray releasesCache;
    private volatile Instant backoffUntil = Instant.EPOCH;

    private final AtomicBoolean checkRunning = new AtomicBoolean(false);
    private final List<ScheduledTask> scheduledChecks = new ArrayList<>();
    private UpdateNotifier.JoinListener joinListener;
    private volatile int scheduledIntervalMinutes = -1;

    /** The server's updater: live GitHub transport, this jar's build info, wall clock. */
    public SourbyUpdater() {
        this(Path.of("auto_update"), HttpTransport.jdk(), BuildInfo::load, Clock.systemUTC(),
            ReleaseSignatureVerifier.fromClasspath(), UpdateRollbackGuard.SERVER);
    }

    /** Test seam: every external dependency injected. */
    SourbyUpdater(Path autoUpdateDir, HttpTransport transport, Supplier<BuildInfo> buildInfo, Clock clock,
                  ReleaseSignatureVerifier signatures, UpdateRollbackGuard rollbackGuard) {
        this(autoUpdateDir, transport, buildInfo, clock, signatures, rollbackGuard,
            () -> RolloutBucket.serverId(autoUpdateDir));
    }

    /** Test seam with the staged-rollout server id injected (read only when the manifest is enforced). */
    SourbyUpdater(Path autoUpdateDir, HttpTransport transport, Supplier<BuildInfo> buildInfo, Clock clock,
                  ReleaseSignatureVerifier signatures, UpdateRollbackGuard rollbackGuard,
                  Supplier<String> rolloutServerId) {
        this.rolloutServerId = rolloutServerId;
        this.autoUpdateDir = autoUpdateDir;
        this.stageDir = autoUpdateDir.resolve("sourbycraft");
        this.corePathFile = autoUpdateDir.resolve("core.path");
        this.latestPathFile = stageDir.resolve("latest.path");
        this.transport = transport;
        this.buildInfo = buildInfo;
        this.clock = clock;
        this.signatures = signatures;
        this.rollbackGuard = rollbackGuard;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Start (or restart) the updater. Idempotent within a boot: schedules one async daily check per
     * configured time on Paper's region-safe AsyncScheduler (works unchanged on Folia/Canvas region
     * threading and on plain Paper). Called from the boot hook once the config is loaded and the
     * internal plugin handle is available.
     */
    public synchronized void start() {
        stop();
        ApplyMode mode = ApplyMode.parse(Config.mode());
        if (mode == ApplyMode.OFF) {
            SourbyLogger.info("auto-updater: mode=off, no checks scheduled.");
            return;
        }

        try {
            Files.createDirectories(stageDir);
        } catch (IOException e) {
            SourbyLogger.warn("auto-updater: could not create stage dir " + stageDir + ": " + e.getMessage());
        }

        Plugin owner = MinecraftInternalPlugin.INSTANCE;
        if (owner == null) {
            SourbyLogger.warn("auto-updater: no plugin handle; checks not scheduled.");
            return;
        }

        java.util.List<String> times = Config.checkTimes();
        if (times.isEmpty()) {
            SourbyLogger.warn("auto-updater enabled but updater.check-times is empty; no checks scheduled.");
            return;
        }

        LocalTime now = LocalTime.now();
        int scheduled = 0;
        for (String time : times) {
            try {
                String[] parts = time.split(":");
                LocalTime at = LocalTime.of(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                Duration delay = Duration.between(now, at);
                if (delay.isNegative()) delay = delay.plusDays(1);
                track(Bukkit.getAsyncScheduler().runAtFixedRate(
                    owner,
                    task -> checkSafely(),
                    Math.max(1L, delay.toMillis()),
                    DAILY_MILLIS,
                    TimeUnit.MILLISECONDS
                ));
                scheduled++;
            } catch (Exception e) {
                SourbyLogger.warn("auto-updater: ignoring illegal check time '" + time + "'");
            }
        }

        // Notify permission holders who join AFTER an update was detected.
        try {
            joinListener = new UpdateNotifier.JoinListener();
            Bukkit.getPluginManager().registerEvents(joinListener, owner);
        } catch (Throwable t) {
            joinListener = null;
            SourbyLogger.warn("auto-updater: could not register the join-notify listener: " + t.getMessage());
        }

        // One-shot check shortly after boot so a release published while the server was down is
        // picked up within minutes, not at the next daily slot.
        track(Bukkit.getAsyncScheduler().runDelayed(owner, task -> checkSafely(), 2, TimeUnit.MINUTES));

        scheduleIntervalCheck(owner, Bukkit.getAsyncScheduler(), Config.checkIntervalMinutes());

        SourbyLogger.info("auto-updater started: repo=" + Config.repo()
            + " channel=" + resolveChannel().suffix()
            + " mode=" + mode.name().toLowerCase(Locale.ROOT)
            + " times=" + times
            + (scheduledIntervalMinutes > 0 ? " interval=" + scheduledIntervalMinutes + "m" : "")
            + " signatures=" + (signatures.isActive() ? "enforced" : "not enforced")
            + " (AsyncScheduler)");
    }

    /**
     * Builds the every-N-minutes check (none for 0) and remembers N as the interval this process runs,
     * so {@code /update status} can tell it apart from a value a later reload left in the config.
     */
    synchronized void scheduleIntervalCheck(Plugin owner, io.papermc.paper.threadedregions.scheduler.AsyncScheduler scheduler,
                                            int intervalMin) {
        if (intervalMin > 0) {
            track(scheduler.runAtFixedRate(
                owner,
                task -> checkSafely(),
                TimeUnit.MINUTES.toMillis(intervalMin),
                TimeUnit.MINUTES.toMillis(intervalMin),
                TimeUnit.MILLISECONDS
            ));
        }
        scheduledIntervalMinutes = Math.max(0, intervalMin);
    }

    /**
     * The check interval of the schedule this updater is running, in minutes (0 = daily times only), or
     * -1 when it has built no schedule in this process (disabled, mode off, stopped). Not the config value.
     */
    public int runningIntervalMinutes() {
        return scheduledIntervalMinutes;
    }

    void track(ScheduledTask task) {
        scheduledChecks.add(task);
    }

    /** Cancel only this updater's scheduled checks and listener. */
    public synchronized void stop() {
        for (ScheduledTask task : scheduledChecks) {
            task.cancel();
        }
        scheduledChecks.clear();
        scheduledIntervalMinutes = -1;
        if (joinListener != null) HandlerList.unregisterAll(joinListener);
        joinListener = null;
    }

    // ------------------------------------------------------------------ check

    private void checkSafely() {
        try {
            if (tryCheck(null).isEmpty()) {
                SourbyLogger.info("auto-updater: a check is already running, skipping.");
                return;
            }
        } catch (Throwable t) {
            SourbyLogger.warn("auto-updater check failed (non-fatal): " + t.getMessage());
        }
        // Recover missing reviewed Via pins on the same cadence (own try/catch); preserve installed jars.
        try {
            ViaAutoUpdate.check();
        } catch (Throwable t) {
            SourbyLogger.warn("via-updater failed (non-fatal): " + t.getMessage());
        }
    }

    /**
     * Run one check unless another one (scheduled or {@code /update}) is running; empty when it is.
     * {@code overrideMode} as for {@link #check}.
     */
    public Optional<Outcome> tryCheck(@Nullable ApplyMode overrideMode) {
        if (!checkRunning.compareAndSet(false, true)) return Optional.empty();
        try {
            return Optional.of(check(overrideMode));
        } finally {
            checkRunning.set(false);
        }
    }

    /**
     * Run one check; {@code overrideMode} (e.g. from {@code /update apply}) wins over the
     * configured mode, letting an operator force a download+apply on a notify-configured server.
     * Callers outside this package go through {@link #tryCheck}, so two checks never stage at once.
     */
    Outcome check(@Nullable ApplyMode overrideMode) {
        ApplyMode mode = overrideMode != null ? overrideMode : ApplyMode.parse(Config.mode());
        if (mode == ApplyMode.OFF) return Outcome.DISABLED;

        if (rollbackGuard.isHeld()) {
            SourbyLogger.error("auto-updater: HELD — " + rollbackGuard.holdReason()
                + " No check or apply runs until an operator runs /update resume (or deletes "
                + rollbackGuard.stateFile() + ").");
            return Outcome.HELD;
        }

        Instant now = clock.instant();
        if (now.isBefore(backoffUntil)) {
            SourbyLogger.info("auto-updater: rate-limited by GitHub; next check allowed at " + backoffUntil + ".");
            return Outcome.RATE_LIMITED;
        }

        BuildInfo info = buildInfo.get();
        String currentVersion = info.version();
        BuildVersion own = ownBuildVersion(info);
        String runningMc = runningMinecraftVersion(info);
        UpdateChannel channel = resolveChannel();

        final String reposBase;
        try {
            reposBase = UpdateOverrides.reposBase();
        } catch (IllegalArgumentException refused) {
            // Set but not acceptable: do nothing rather than fall back to the real GitHub.
            SourbyLogger.error("auto-updater: " + refused.getMessage() + "; no check is made.");
            return Outcome.DISABLED;
        }
        if (!GITHUB_API.equals(reposBase)) {
            SourbyLogger.warn("auto-updater: TEST OVERRIDE -D" + UpdateOverrides.API_BASE_PROPERTY + " is set; the releases"
                + " listing comes from " + reposBase + " instead of GitHub.");
        }
        Listing listing = fetchReleases(reposBase + Config.repo() + "/releases?per_page=100");
        if (listing.rateLimited()) return Outcome.RATE_LIMITED;
        // Releases for this Minecraft version are selected, staged and applied; the newest release
        // for another one is only announced, and never hides a newer release for this one.
        ReleaseInfo latest = listing.releases() == null ? null : latestForChannel(listing.releases(), channel, runningMc, true);
        ReleaseInfo otherMc = listing.releases() == null ? null : latestForChannel(listing.releases(), channel, runningMc, false);
        if (latest == null && otherMc == null) {
            SourbyLogger.info("auto-updater: no matching " + channel.suffix()
                + " release found for repo " + Config.repo() + " (up to date, or none published yet).");
            UpdateNotifier.clearPending();
            return Outcome.NO_RELEASE;
        }
        String newestTag = latest != null ? latest.tagName : otherMc.tagName;
        if (latest != null && !isNewerThanRunning(latest.tagName, currentVersion, own)) latest = null;
        if (otherMc != null && !isNewerThanRunning(otherMc.tagName, currentVersion, own)) otherMc = null;
        if (latest == null && otherMc == null) {
            SourbyLogger.info("auto-updater: already up to date on channel "
                + channel.suffix() + " (current " + currentVersion + " build " + own
                + ", latest " + newestTag + ").");
            UpdateNotifier.clearPending();
            return Outcome.UP_TO_DATE;
        }
        // Signed release manifest (staged rollout + yank list): enforced under the same rule as the
        // SEC-2 jar signature, i.e. only when this build ships the release public key. Runs before
        // the announcement, so a yanked or not-yet-rolled-out release is neither announced nor staged.
        Outcome withoutSameMc = Outcome.UP_TO_DATE;
        if (signatures.isActive()) {
            Selection selection = selectByManifest(listing.releases(), channel, currentVersion, own, runningMc);
            latest = selection.release();
            otherMc = selection.otherMinecraft();
            withoutSameMc = selection.outcome();
        }
        if (otherMc != null) {
            SourbyLogger.warn("auto-updater: " + otherMc.tagName + " targets Minecraft " + coreOf(otherMc.tagName)
                + " but this server runs " + runningMc + "; it is announced, never staged. Crossing a Minecraft "
                + "version is a manual upgrade (worlds, plugins, data formats).");
            UpdateNotifier.announce(currentVersion, otherMc.tagName, channel, otherMc.htmlUrl, otherMc.notesSummary());
            if (latest == null) {
                return withoutSameMc == Outcome.MANIFEST_REJECTED ? withoutSameMc
                    : mode == ApplyMode.NOTIFY ? Outcome.NOTIFIED : Outcome.MC_VERSION_MISMATCH;
            }
        } else if (latest == null) {
            UpdateNotifier.clearPending();
            return withoutSameMc;
        }

        if (latest.tagName.equals(readMarker("applied.tag")) && !appliedButReplaced(latest.tagName, own, latest.sha256)) {
            // Already swapped to this tag (waiting for/after restart, rolled back, or a pre-r6 jar whose
            // build number was not stamped from the release counter). Never re-download or re-restart.
            SourbyLogger.info("auto-updater: " + latest.tagName + " already applied; nothing to do.");
            return Outcome.UP_TO_DATE;
        }

        // An apply of this very release failed here before: the next try is the operator's.
        if (overrideMode == null && mode != ApplyMode.NOTIFY && (latest.tagName.equals(readMarker(UpdateApplier.APPLY_FAILED_FILE))
                || latest.tagName.equals(readMarker(UpdateApplier.LEGACY_PENDING_FILE)))) {
            SourbyLogger.info("auto-updater: an earlier apply of " + latest.tagName + " failed on this server (auto_update/"
                + UpdateApplier.APPLY_FAILED_FILE + "); this check only announces it. /update apply tries again.");
            mode = ApplyMode.NOTIFY;
        }

        // An update exists. notify + (optionally) stage.
        UpdateNotifier.announce(currentVersion, latest.tagName, channel, latest.htmlUrl, latest.notesSummary());

        if (mode == ApplyMode.NOTIFY) {
            return Outcome.NOTIFIED;
        }
        // Stage as well as auto: the countdown copies the staged jar, which a second stage must not
        // replace or (after a failed re-check) delete under it.
        if (UpdateApplier.inFlight()) {
            SourbyLogger.info("auto-updater: an apply is already counting down; not staging " + latest.tagName + " again.");
            return Outcome.APPLYING;
        }

        // stage/auto: download + verify + place in the stage dir (latest is for this Minecraft version).
        try {
            Path staged = downloadAndStage(latest);
            writePath(corePathFile, staged);
            writePath(latestPathFile, staged);
            if (mode == ApplyMode.AUTO) {
                SourbyLogger.info("auto-updater: staged " + latest.tagName + " at " + staged.toAbsolutePath() + ".");
                return UpdateApplier.scheduleApplyAndRestart(latest.tagName, staged, latest.sha256,
                    Config.restartDelaySeconds(), Config.restartMode()) ? Outcome.APPLYING : Outcome.APPLY_FAILED;
            }
            SourbyLogger.info("auto-updater: staged " + latest.tagName + " at " + staged.toAbsolutePath()
                + " (auto_update/core.path points at it). A restart does NOT install it: run /update apply, or stop"
                + " the server and copy it over the launch jar by hand.");
            return Outcome.STAGED;
        } catch (LauncherRefusedException e) {
            SourbyLogger.warn("auto-updater: NOT staging " + latest.tagName + ": " + e.getMessage()
                + " Moving to a different launcher is a manual upgrade; the downloaded copy was deleted and"
                + " this server keeps running its current jar.");
            return Outcome.CLIP_INCOMPATIBLE;
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: failed to stage " + latest.tagName + ": " + e.getMessage());
            return Outcome.STAGE_FAILED;
        }
    }

    /** Instant until which checks are skipped because GitHub asked us to back off. */
    Instant backoffUntil() {
        return backoffUntil;
    }

    // ------------------------------------------------------------------ release-number ordering

    /**
     * Newer-ness for our per-release tags: cores compare first ({@code 26.11 > 26.2}); equal cores
     * fall back to the tag's release version {@code rXXX} versus THIS jar's stamped build number.
     * Supports composite release identifiers: {@code v26.2-r43}, {@code v26.2-r43.1} (hotfix),
     * {@code v26.2-r44-hotfix}. Without this fallback, every tag on the same MC version line
     * compares equal to the running version and no update is ever taken.
     */
    static boolean isNewerThanRunning(String tag, String currentVersion) {
        return isNewerThanRunning(tag, currentVersion, ownBuildVersion());
    }

    static boolean isNewerThanRunning(String tag, String currentVersion, BuildVersion own) {
        int coreCmp = SemVer.compare(coreOf(tag), coreOf(currentVersion));
        if (coreCmp != 0) return coreCmp > 0;
        BuildVersion tagRel = releaseVersionOf(tag);
        return !tagRel.isUnknown() && !own.isUnknown() && tagRel.compareTo(own) > 0;
    }

    /**
     * Order two release TAGS: numeric core first, then the composite release version. Supports
     * dotted ({@code r43.1}) and suffixed ({@code r44-hotfix}) release identifiers. NOT plain
     * {@link SemVer#compare}, which tokenises {@code "26.2-r10"} into {@code [26,2,"r10"]} and then
     * compares {@code "r10"} vs {@code "r9"} LEXICALLY — {@code "r10" < "r9"} — so it ranks r10
     * BELOW r9. That made {@code fetchLatestForChannel} keep picking r9 as "latest" and the
     * updater got stuck one release behind forever.
     */
    static int compareTags(String a, String b) {
        int coreCmp = SemVer.compare(coreOf(a), coreOf(b));
        if (coreCmp != 0) return coreCmp;
        return releaseVersionOf(a).compareTo(releaseVersionOf(b));
    }

    /** Version core with any {@code -rXXX} release counter AND channel suffix stripped. */
    static String coreOf(String version) {
        String v = SemVer.stripChannel(version);
        return RELEASE_NUM.matcher(v).replaceFirst("");
    }

    /**
     * The Minecraft version this server runs, for the same-version gate: the build-info
     * {@code mcVersion} stamp when present, else the running version's core ({@code 26.2-REL} →
     * {@code 26.2}). A dev build without either yields {@code "unknown"}, which matches no tag — a
     * build that cannot say which Minecraft it is never stages anything.
     */
    static String runningMinecraftVersion(BuildInfo info) {
        String mc = info.mcVersion();
        if (mc != null && !mc.isBlank() && !"unknown".equalsIgnoreCase(mc.trim())) return mc.trim();
        String core = coreOf(info.version());
        return core.isBlank() || "dev".equalsIgnoreCase(core) ? "unknown" : core;
    }

    /** True when the tag's version core names the same Minecraft version as {@code runningMc}. */
    static boolean sameMinecraftVersion(String tag, String runningMc) {
        if ("unknown".equals(runningMc)) return false;
        String tagMc = coreOf(tag);
        if (tagMc.isBlank()) return false;
        // Both sides must look like a numeric version; SemVer.compare would otherwise rank the
        // alpha token "unknown" below any number and never report equality anyway.
        return SemVer.compare(tagMc, runningMc) == 0;
    }

    /**
     * Matches a release identifier after {@code -r}: integer ({@code -r43}), dotted
     * ({@code -r43.1}), or suffixed ({@code -r44-hotfix}). The captured group is the full
     * composite release version string, parsed by {@link BuildVersion}.
     */
    private static final java.util.regex.Pattern RELEASE_NUM =
        java.util.regex.Pattern.compile("-r(\\d[\\w.-]*)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Extract the composite release version from a tag. {@code v26.2-r43} -> {@code 43},
     * {@code v26.2-r43.1} -> {@code 43.1}, {@code v26.2-r44-hotfix} -> {@code 44-hotfix}.
     * Returns {@link BuildVersion#UNKNOWN} when the tag has no release counter.
     */
    static BuildVersion releaseVersionOf(String tag) {
        if (tag == null) return BuildVersion.UNKNOWN;
        var m = RELEASE_NUM.matcher(SemVer.stripChannel(tag));
        return m.find() ? BuildVersion.parse(m.group(1)) : BuildVersion.UNKNOWN;
    }

    /**
     * This jar's stamped build version for update comparison. Uses the raw {@code buildNumber}
     * field from {@code sourbycraft-build.properties} (e.g. {@code "43.1"}), which is the build
     * number without the platform suffix. Supports composite versions: {@code "43"}, {@code "43.1"},
     * {@code "44-hotfix"}.
     */
    static BuildVersion ownBuildVersion() {
        try {
            return ownBuildVersion(BuildInfo.load());
        } catch (Throwable t) {
            return BuildVersion.UNKNOWN;
        }
    }

    static BuildVersion ownBuildVersion(BuildInfo info) {
        try {
            return BuildVersion.parse(info.buildNumber());
        } catch (Throwable t) {
            return BuildVersion.UNKNOWN;
        }
    }

    /** The trimmed contents of a marker file in the auto_update dir, or {@code null} when absent or unreadable. */
    private @Nullable String readMarker(String name) {
        try {
            Path f = autoUpdateDir.resolve(name);
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * True when the update {@code applied.tag} names was confirmed by the rollback guard
     * ({@link UpdateRollbackGuard.Phase#OK} for {@code tag}), this server runs an older build (callers
     * only ask about a release newer than the running build) and the launch jar the guard recorded no
     * longer has the release's SHA-256: it was replaced outside the updater (a container recreated
     * from an older image keeps {@code auto_update} on its volume). Any other guard state, including
     * none after {@code /update resume}, an unknown digest or a launch jar that cannot be read, keeps
     * the marker in force.
     */
    private boolean appliedButReplaced(String tag, BuildVersion own, @Nullable String releaseSha256) {
        Optional<UpdateRollbackGuard.State> state = rollbackGuard.state();
        if (releaseSha256 == null || state.isEmpty() || state.get().phase() != UpdateRollbackGuard.Phase.OK
                || !tag.equals(state.get().tag())) {
            return false;
        }
        Path launch = state.get().launchJar();
        String onDisk = launch != null && Files.isRegularFile(launch) ? sha256(launch) : null;
        if (onDisk == null || onDisk.equalsIgnoreCase(releaseSha256)) return false;
        SourbyLogger.warn("auto-updater: " + tag + " was applied and confirmed on this server, but it now runs build "
            + own + " and " + launch + " is no longer that release: the launch jar was replaced outside the updater "
            + "(for example a container recreated from an older image). Offering " + tag + " again.");
        return true;
    }

    private UpdateChannel resolveChannel() {
        // Operator override wins; else derive from this build's own version suffix.
        UpdateChannel forced = UpdateChannel.parseConfig(Config.channel());
        if (forced != null) return forced;
        return UpdateChannel.parseFromVersion(buildInfo.get().version());
    }

    // ------------------------------------------------------------------ GitHub

    /** Result of the releases listing request. */
    private record Listing(@Nullable JsonArray releases, boolean rateLimited) {
        static final Listing FAILED = new Listing(null, false);
        static final Listing RATE_LIMITED = new Listing(null, true);
    }

    /**
     * GET the releases listing as a conditional request. A {@code 304 Not Modified} answers from the
     * cached listing of the previous check; a new {@code ETag} replaces the cache; a rate-limit
     * response arms the backoff window.
     */
    private Listing fetchReleases(String url) {
        synchronized (listingLock) {
            return fetchReleasesLocked(url);
        }
    }

    private Listing fetchReleasesLocked(String url) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (releasesEtag != null && releasesCache != null) {
            headers.put("If-None-Match", releasesEtag);
        }
        try (HttpTransport.Response resp = transport.get(url, headers)) {
            int code = resp.status();
            if (code == 304 && releasesCache != null) {
                SourbyLogger.info("auto-updater: releases unchanged since last check (304).");
                return new Listing(releasesCache, false);
            }
            if (code == 404) {
                SourbyLogger.info("auto-updater: repo/releases not found (404) for " + url
                    + " — no releases yet, or check updater.repository.");
                return Listing.FAILED;
            }
            if (code == 429 || code == 403) {
                Optional<Duration> wait = retryAfter(resp, clock.instant());
                if (wait.isEmpty() && code == 429) wait = Optional.of(DEFAULT_BACKOFF);
                if (wait.isPresent()) {
                    backoffUntil = clock.instant().plus(wait.get());
                    SourbyLogger.warn("auto-updater: GitHub returned " + code + " (rate limit); backing off "
                        + wait.get().toSeconds() + "s, next check at " + backoffUntil + ".");
                    return Listing.RATE_LIMITED;
                }
                SourbyLogger.warn("auto-updater: GitHub API returned " + code + " for " + url);
                return Listing.FAILED;
            }
            if (code >= 400) {
                SourbyLogger.warn("auto-updater: GitHub API returned " + code + " for " + url);
                return Listing.FAILED;
            }
            JsonElement el;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.bodyOrEmpty(), StandardCharsets.UTF_8))) {
                el = GSON.fromJson(r, JsonElement.class);
            }
            if (el == null || !el.isJsonArray()) {
                SourbyLogger.warn("auto-updater: unexpected GitHub response shape from " + url);
                return Listing.FAILED;
            }
            JsonArray arr = el.getAsJsonArray();
            String etag = resp.header("ETag");
            if (etag != null && !etag.isBlank()) {
                releasesEtag = etag.trim();
                releasesCache = arr;
            } else {
                releasesEtag = null;
                releasesCache = null;
            }
            return new Listing(arr, false);
        } catch (JsonSyntaxException e) {
            SourbyLogger.warn("auto-updater: failed to parse GitHub response from " + url);
            return Listing.FAILED;
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: request to " + url + " failed: " + e.getMessage());
            return Listing.FAILED;
        }
    }

    /**
     * How long a rate-limited response asks us to wait: {@code Retry-After} as seconds or an HTTP
     * date, else {@code X-RateLimit-Reset} (epoch seconds) when {@code X-RateLimit-Remaining} is 0.
     * Clamped to [{@link #MIN_BACKOFF}, {@link #MAX_BACKOFF}]. Empty when the response gives no hint.
     */
    static Optional<Duration> retryAfter(HttpTransport.Response resp, Instant now) {
        String retry = resp.header("Retry-After");
        if (retry != null && !retry.isBlank()) {
            String v = retry.trim();
            try {
                return Optional.of(clamp(Duration.ofSeconds(Long.parseLong(v))));
            } catch (NumberFormatException notSeconds) {
                try {
                    Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Optional.of(clamp(Duration.between(now, at)));
                } catch (DateTimeParseException ignored) {
                    // fall through to the X-RateLimit headers
                }
            }
        }
        String remaining = resp.header("X-RateLimit-Remaining");
        String reset = resp.header("X-RateLimit-Reset");
        if (remaining != null && reset != null && "0".equals(remaining.trim())) {
            try {
                Instant at = Instant.ofEpochSecond(Long.parseLong(reset.trim()));
                return Optional.of(clamp(Duration.between(now, at)));
            } catch (NumberFormatException ignored) {
                // no usable hint
            }
        }
        return Optional.empty();
    }

    private static Duration clamp(Duration d) {
        if (d.compareTo(MIN_BACKOFF) < 0) return MIN_BACKOFF;
        if (d.compareTo(MAX_BACKOFF) > 0) return MAX_BACKOFF;
        return d;
    }

    /**
     * What a manifest-enforcing check settles on: the release for this Minecraft version to take (or
     * the outcome when there is none), and the newer-Minecraft release that may be announced.
     */
    private record Selection(@Nullable ReleaseInfo release, Outcome outcome, @Nullable ReleaseInfo otherMinecraft) {}

    /**
     * A newer release as seen by the policy pass: its tag and manifest assets always, and the
     * installable jar only when it has one (and is not a prerelease this server ignores). A release
     * without a jar still carries signed yanks that must count.
     */
    private record PolicyCandidate(String tag, @Nullable ReleaseInfo installable,
                                   @Nullable String manifestUrl, long manifestSize,
                                   @Nullable String manifestSignatureUrl, long manifestSignatureSize) {}

    private @Nullable PolicyCandidate policyCandidate(JsonObject rel, UpdateChannel channel) {
        if (getBool(rel, "draft")) return null;
        String tag = getString(rel, "tag_name");
        if (tag == null || tag.isBlank() || !channel.tagMatchesChannel(tag)) return null;
        String manifestUrl = null, sigUrl = null;
        long manifestSize = -1L, sigSize = -1L;
        if (rel.has("assets") && rel.get("assets").isJsonArray()) {
            for (JsonElement ae : rel.getAsJsonArray("assets")) {
                if (!ae.isJsonObject()) continue;
                JsonObject asset = ae.getAsJsonObject();
                String name = getString(asset, "name");
                long size = asset.has("size") && asset.get("size").isJsonPrimitive() ? asset.get("size").getAsLong() : -1L;
                if (ReleaseManifest.ASSET_NAME.equals(name)) {
                    manifestUrl = getString(asset, "browser_download_url");
                    manifestSize = size;
                } else if (ReleaseManifest.SIGNATURE_ASSET_NAME.equals(name)) {
                    sigUrl = getString(asset, "browser_download_url");
                    sigSize = size;
                }
            }
        }
        return new PolicyCandidate(tag, parseRelease(rel, channel), manifestUrl, manifestSize, sigUrl, sigSize);
    }

    /**
     * Pick the newest release newer than the running build that the signed release policy lets this
     * server take, and the newest newer-Minecraft release it may announce. Newer releases on the
     * channel are inspected newest first <b>whether or not they still have a jar</b>, so deleting a
     * newer release's jar cannot hide its yanks: first those for a newer Minecraft version (they rank
     * above every tag of this one; only their yanks and the newest announceable one matter), then
     * those for this Minecraft version, at most {@link #MAX_MANIFEST_CANDIDATES} of each.
     *
     * <ul>
     *   <li>Missing, unsigned, tampered, malformed or mis-bound manifest: stop with
     *       {@link Outcome#MANIFEST_REJECTED} (its yanks cannot be learned, so nothing older may be taken).</li>
     *   <li>Every authentic manifest adds its yanks, even when expired or older than the revision
     *       already accepted for its tag; yanks also persist in {@link ReleasePolicyState}.</li>
     *   <li>Only a <i>fresh</i> manifest (not expired, {@code policy_revision} at least the highest
     *       accepted for that tag) can make its release installable (or, for another Minecraft
     *       version, announceable); a stale one is skipped.</li>
     * </ul>
     */
    private Selection selectByManifest(JsonArray releases, UpdateChannel channel, String currentVersion, BuildVersion own,
                                       String runningMc) {
        ReleasePolicyState state;
        try {
            state = ReleasePolicyState.load(autoUpdateDir);
        } catch (IOException e) {
            SourbyLogger.error("auto-updater: release policy state unreadable (" + e.getMessage() + "); refusing every update. "
                + "Delete " + autoUpdateDir.resolve(ReleasePolicyState.FILE) + " to reset it (trust on first use).");
            return new Selection(null, Outcome.MANIFEST_REJECTED, null);
        }
        List<PolicyCandidate> sameMc = new ArrayList<>();
        List<PolicyCandidate> otherMc = new ArrayList<>();
        for (JsonElement el : releases) {
            if (!el.isJsonObject()) continue;
            PolicyCandidate c = policyCandidate(el.getAsJsonObject(), channel);
            if (c != null && isNewerThanRunning(c.tag(), currentVersion, own)) {
                (sameMinecraftVersion(c.tag(), runningMc) ? sameMc : otherMc).add(c);
            }
        }
        sameMc.sort((a, b) -> compareTags(b.tag(), a.tag()));
        otherMc.sort((a, b) -> compareTags(b.tag(), a.tag()));
        List<PolicyCandidate> inspected = new ArrayList<>(otherMc.subList(0, Math.min(otherMc.size(), MAX_MANIFEST_CANDIDATES)));
        inspected.addAll(sameMc.subList(0, Math.min(sameMc.size(), MAX_MANIFEST_CANDIDATES)));
        java.util.Set<String> yanked = new java.util.HashSet<>(state.yanked());
        boolean deferred = false, stale = false, incomplete = false;
        String serverId = null;
        boolean serverIdRead = false;
        Instant now = clock.instant();
        Selection result = null;
        ReleaseInfo announce = null;
        for (PolicyCandidate c : inspected) {
            boolean other = !sameMinecraftVersion(c.tag(), runningMc);
            if (other && announce != null) continue; // the newest announceable one is all an announcement needs
            ReleaseManifest manifest;
            try {
                manifest = fetchManifest(c);
            } catch (Exception e) {
                SourbyLogger.warn("auto-updater: NOT taking " + c.tag() + " or anything older: signed release manifest rejected ("
                    + e.getMessage() + "). This build enforces release manifests (its release key is present).");
                result = new Selection(null, Outcome.MANIFEST_REJECTED, other ? null : announce);
                break;
            }
            yanked.addAll(manifest.yanked());
            state.addYanks(manifest.yanked());
            boolean expired = manifest.expiredAt(now);
            boolean rolledBack = manifest.policyRevision() < state.revision(c.tag());
            if (!expired && !rolledBack) state.acceptRevision(c.tag(), manifest.policyRevision());
            if (yanked.contains(c.tag())) {
                SourbyLogger.warn("auto-updater: " + c.tag() + " is YANKED by a signed release manifest; it is never staged.");
                continue;
            }
            if (c.installable() == null) {
                SourbyLogger.warn("auto-updater: " + c.tag() + " has no installable jar asset; its signed yanks count, it is skipped.");
                incomplete |= !other;
                continue;
            }
            if (expired || rolledBack) {
                SourbyLogger.warn("auto-updater: NOT taking " + c.tag() + ": its release manifest is "
                    + (expired ? "expired (expires_at " + manifest.expiresAt() + ")" : "older (policy_revision "
                    + manifest.policyRevision() + ") than the revision " + state.revision(c.tag()) + " already accepted")
                    + ". Its yanks still count.");
                stale |= !other;
                continue;
            }
            if (!serverIdRead) {
                serverId = rolloutServerId.get();
                serverIdRead = true;
            }
            int bucket = RolloutBucket.bucket(serverId, c.tag());
            if (bucket >= manifest.rolloutPercent()) {
                SourbyLogger.info("auto-updater: " + c.tag() + " is rolling out to " + manifest.rolloutPercent()
                    + "% of servers and has not reached this one yet (bucket " + bucket + ").");
                deferred |= !other;
                continue;
            }
            if (other) {
                announce = c.installable();
                continue;
            }
            result = new Selection(c.installable(), Outcome.NOTIFIED, announce);
            break;
        }
        try {
            state.save(autoUpdateDir);
        } catch (IOException e) {
            SourbyLogger.warn("auto-updater: could not persist the release policy state (" + e.getMessage()
                + "); refusing to install on knowledge that would not survive a restart.");
            return new Selection(null, Outcome.MANIFEST_REJECTED, null);
        }
        if (result != null) return result;
        Outcome none = sameMc.isEmpty() ? Outcome.UP_TO_DATE
            : stale ? Outcome.MANIFEST_REJECTED
            : deferred ? Outcome.ROLLOUT_DEFERRED
            : incomplete ? Outcome.NO_RELEASE
            : Outcome.YANKED;
        return new Selection(null, none, announce);
    }

    /**
     * Download (bounded), verify the Ed25519 signature over the exact bytes, parse and bind to the
     * release: tag always; jar asset name and digest when the release still has a jar.
     */
    private ReleaseManifest fetchManifest(PolicyCandidate c) throws IOException, GeneralSecurityException,
            ReleaseManifest.InvalidManifestException {
        if (c.manifestUrl() == null || c.manifestSignatureUrl() == null) {
            throw new IOException("the release has no " + (c.manifestUrl() == null ? ReleaseManifest.ASSET_NAME
                : ReleaseManifest.SIGNATURE_ASSET_NAME) + " asset");
        }
        byte[] bytes = fetchBounded(c.manifestUrl(), c.manifestSize(), ReleaseManifest.MAX_BYTES, "manifest");
        byte[] sig = fetchBounded(c.manifestSignatureUrl(), c.manifestSignatureSize(), MAX_SIGNATURE_BYTES, "manifest signature");
        signatures.verifyBytesOrThrow(bytes, sig);
        ReleaseManifest manifest = ReleaseManifest.parse(bytes);
        ReleaseInfo jar = c.installable();
        if (jar != null) {
            manifest.requireBoundTo(c.tag(), jar.assetName, jar.sha256);
        } else {
            manifest.requireTagBound(c.tag());
        }
        return manifest;
    }

    private byte[] fetchBounded(String url, long declaredSize, long limit, String what) throws IOException {
        if (declaredSize > limit) throw new IOException(what + " asset is " + declaredSize + " bytes");
        try (HttpTransport.Response resp = getAsset(url, what)) {
            if (resp.status() >= 400) throw new IOException(what + " download returned HTTP " + resp.status());
            if (resp.contentLength() > limit) throw new IOException(what + " Content-Length " + resp.contentLength() + " exceeds the bound");
            return readBounded(new DeadlineInputStream(resp.bodyOrEmpty(), MAX_DOWNLOAD_TIME), limit);
        }
    }

    /** GET a release asset; refuses a URL that is not https (plain http only as {@link UpdateOverrides#assetUrlAllowed} allows). */
    private HttpTransport.Response getAsset(String url, String what) throws IOException {
        if (url == null || !UpdateOverrides.assetUrlAllowed(url)) {
            throw new IOException("refusing to download the " + what + " from " + url + ": release assets must use https");
        }
        return transport.get(url, Map.of("Accept", "application/octet-stream"));
    }

    /**
     * The highest installable release on the channel whose Minecraft version is ({@code sameMc}) or
     * is not ({@code !sameMc}) {@code runningMc}.
     */
    private @Nullable ReleaseInfo latestForChannel(JsonArray releases, UpdateChannel channel, String runningMc, boolean sameMc) {
        ReleaseInfo best = null;
        for (JsonElement el : releases) {
            if (!el.isJsonObject()) continue;
            ReleaseInfo info = parseRelease(el.getAsJsonObject(), channel);
            if (info == null || sameMinecraftVersion(info.tagName, runningMc) != sameMc) continue;
            if (best == null || compareTags(info.tagName, best.tagName) > 0) {
                best = info;
            }
        }
        return best;
    }

    private @Nullable ReleaseInfo parseRelease(JsonObject rel, UpdateChannel channel) {
        if (getBool(rel, "draft")) return null;
        boolean prerelease = getBool(rel, "prerelease");
        if (prerelease && !Config.allowPrerelease()) return null;

        String tag = getString(rel, "tag_name");
        if (tag == null || tag.isBlank()) return null;
        if (!channel.tagMatchesChannel(tag)) return null; // channel gate

        JsonArray assets = rel.has("assets") && rel.get("assets").isJsonArray()
            ? rel.getAsJsonArray("assets") : null;
        if (assets == null || assets.isEmpty()) return null;

        JsonObject jar = null;
        for (JsonElement ae : assets) {
            if (!ae.isJsonObject()) continue;
            JsonObject asset = ae.getAsJsonObject();
            String name = getString(asset, "name");
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (getString(asset, "browser_download_url") == null) continue;
            jar = asset;
            break;
        }
        if (jar == null) return null;
        String jarName = getString(jar, "name");

        // The detached signature, when the release publishes one, is "<jar name>.sig".
        String sigUrl = null;
        long sigSize = -1L;
        for (JsonElement ae : assets) {
            if (!ae.isJsonObject()) continue;
            JsonObject asset = ae.getAsJsonObject();
            if ((jarName + ReleaseSignatureVerifier.SIGNATURE_SUFFIX).equals(getString(asset, "name"))) {
                sigUrl = getString(asset, "browser_download_url");
                sigSize = asset.has("size") && asset.get("size").isJsonPrimitive() ? asset.get("size").getAsLong() : -1L;
                break;
            }
        }

        long size = jar.has("size") && jar.get("size").isJsonPrimitive() ? jar.get("size").getAsLong() : -1L;
        return new ReleaseInfo(
            tag,
            getString(rel, "html_url"),
            getString(rel, "body"),
            jarName,
            getString(jar, "browser_download_url"),
            size,
            normalizeSha256(getString(jar, "digest")),
            sigUrl,
            sigSize
        );
    }

    // ------------------------------------------------------------------ download + verify + stage

    private Path downloadAndStage(ReleaseInfo info) throws IOException, GeneralSecurityException {
        Path target = stagedPath(info);
        if (info.declaredSize > MAX_JAR_BYTES) {
            throw new IOException("refusing " + info.assetName + ": it declares " + info.declaredSize
                + " bytes, more than the " + MAX_JAR_BYTES + "-byte ceiling");
        }
        Files.createDirectories(stageDir);

        // A copy staged by an earlier check that still has the release digest is not downloaded again.
        if (info.sha256 != null && Files.isRegularFile(target) && info.sha256.equalsIgnoreCase(sha256(target))) {
            SourbyLogger.info("auto-updater: " + target.getFileName() + " is already staged with the release's SHA-256; "
                + "not downloading it again.");
            if (signatures.isActive()) {
                verifySignatureOrThrow(target, info);
            }
            verifyLauncherOrThrow(target, info);
            return target;
        }

        long limit = info.declaredSize > 0 ? info.declaredSize : MAX_JAR_BYTES;
        // A fresh name per download: nothing else ever writes into this file.
        Path temp = Files.createTempFile(stageDir, info.assetName + ".", ".part");
        boolean staged = false;
        try {
            try (HttpTransport.Response resp = getAsset(info.downloadUrl, info.assetName)) {
                if (resp.status() >= 400) {
                    throw new IOException("download of " + info.assetName + " returned HTTP " + resp.status());
                }
                long advertised = resp.contentLength();
                if (advertised > limit) {
                    throw new IOException("refusing " + info.assetName + ": Content-Length " + advertised
                        + " exceeds the " + limit + "-byte bound; nothing was read");
                }
                copyBounded(new DeadlineInputStream(resp.bodyOrEmpty(), MAX_DOWNLOAD_TIME), temp, limit);
            }

            verifyOrThrow(temp, info);
            if (signatures.isActive()) {
                verifySignatureOrThrow(temp, info);
            }
            // Authentic bytes are still not necessarily a jar this server can hand over to (SEC-4).
            verifyLauncherOrThrow(temp, info);

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            staged = true;
            return target;
        } finally {
            if (!staged) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            }
        }
    }

    /** Where {@code info}'s jar is staged; refuses an asset name that is not a plain {@code .jar} file name. */
    private Path stagedPath(ReleaseInfo info) throws IOException {
        if (!SAFE_ASSET_NAME.matcher(info.assetName).matches()) {
            throw new IOException("refusing asset name '" + info.assetName + "': only letters, digits, '.', '_' and '-'"
                + " ending in .jar are staged");
        }
        Path target = stageDir.resolve(info.assetName);
        Path base = stageDir.toAbsolutePath().normalize();
        if (!base.equals(target.toAbsolutePath().normalize().getParent())) {
            throw new IOException("refusing asset name '" + info.assetName + "': it resolves outside " + stageDir);
        }
        return target;
    }

    /**
     * Fails a read once a total time budget has passed: an overall deadline on top of the
     * transport's per-read timeout, so a slow drip cannot keep a download open indefinitely.
     */
    static final class DeadlineInputStream extends FilterInputStream {
        private final long deadlineNanos;
        private final Duration budget;

        DeadlineInputStream(InputStream in, Duration budget) {
            super(in);
            this.budget = budget;
            this.deadlineNanos = System.nanoTime() + budget.toNanos();
        }

        private void checkDeadline() throws IOException {
            if (System.nanoTime() - deadlineNanos > 0) {
                throw new IOException("download took longer than " + budget.toMinutes() + " minutes; aborted");
            }
        }

        @Override
        public int read() throws IOException {
            checkDeadline();
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkDeadline();
            return super.read(b, off, len);
        }
    }

    /**
     * Stream {@code in} to {@code target}, aborting (and throwing) as soon as more than {@code limit}
     * bytes have arrived, so an oversize or endless body never fills the disk before the size check.
     * Returns the number of bytes written.
     */
    static long copyBounded(InputStream in, Path target, long limit) throws IOException {
        long total = 0L;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > limit) {
                    throw new IOException("download exceeded the " + limit + "-byte bound; aborted after " + total + " bytes");
                }
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    /** Read at most {@code limit} bytes into memory; throws when the stream has more. */
    static byte[] readBounded(InputStream in, long limit) throws IOException {
        byte[] data = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
        if (data.length > limit) {
            throw new IOException("response exceeded the " + limit + "-byte bound");
        }
        return data;
    }

    /**
     * Verify a downloaded jar before it is staged for execution. Order: non-empty -> declared size
     * (when known) -> <b>mandatory SHA-256</b> against the release asset digest -> valid-ZIP/JAR open.
     * A staged jar becomes the launch jar once it is applied, so integrity verification is REQUIRED: an asset
     * whose release publishes no SHA-256 digest is refused (size + zip-validity alone do not prove the
     * bytes are the genuine release — a MITM or tampered mirror can forge both). Any failure deletes
     * the file and throws so it is never staged.
     */
    private void verifyOrThrow(Path file, ReleaseInfo info) throws IOException {
        long actualSize = Files.size(file);
        if (actualSize <= 0) {
            fail(file, "downloaded file is empty");
        }
        if (info.declaredSize > 0 && actualSize != info.declaredSize) {
            fail(file, "size mismatch: expected " + info.declaredSize + " got " + actualSize);
        }
        // Mandatory integrity check: never stage executable code without a verified digest.
        if (info.sha256 == null) {
            fail(file, "refusing to stage " + info.assetName + ": release publishes no SHA-256 digest "
                + "(integrity cannot be verified; set the update to a release that publishes asset digests)");
        }
        String actual = sha256(file);
        if (actual == null || !actual.equalsIgnoreCase(info.sha256)) {
            fail(file, "SHA-256 mismatch for " + info.assetName);
        }
        SourbyLogger.info("auto-updater: SHA-256 verified for " + info.assetName);
        if (!isValidZip(file)) {
            fail(file, "not a valid zip/jar: " + info.assetName);
        }
    }

    /**
     * The launcher gate: the jar's embedded SourbyClip must be one this server can hand over to
     * ({@link LauncherCompatibility}). Runs after the integrity checks, so only authentic bytes are
     * inspected; a refusal deletes the file and throws {@link LauncherRefusedException}, which
     * {@link #check} maps to {@link Outcome#CLIP_INCOMPATIBLE}. Missing metadata fails closed.
     */
    private void verifyLauncherOrThrow(Path file, ReleaseInfo info) throws IOException {
        LauncherCompatibility.Verdict verdict = LauncherCompatibility.inspect(file);
        if (verdict.compatible()) {
            SourbyLogger.info("auto-updater: SourbyClip " + verdict.version() + " (protocol "
                + verdict.protocol() + ") in " + info.assetName + " is compatible.");
            return;
        }
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        throw new LauncherRefusedException(verdict);
    }

    /** The staged jar carries a launcher this server cannot hand over to; see {@link LauncherCompatibility}. */
    static final class LauncherRefusedException extends IOException {
        private final LauncherCompatibility.Verdict verdict;

        LauncherRefusedException(LauncherCompatibility.Verdict verdict) {
            super("the release's launcher is not compatible [" + verdict.reason() + "]: " + verdict.detail() + ".");
            this.verdict = verdict;
        }

        LauncherCompatibility.Verdict verdict() {
            return verdict;
        }
    }

    /**
     * Signature enforcement (only when this build ships a release key): the release must carry
     * {@code <asset>.sig} and it must verify over the downloaded bytes. The signature download is
     * bounded to {@link #MAX_SIGNATURE_BYTES}.
     */
    private void verifySignatureOrThrow(Path file, ReleaseInfo info) throws IOException, GeneralSecurityException {
        if (info.signatureUrl == null) {
            fail(file, "refusing to stage " + info.assetName + ": this build enforces release signatures and the release has no "
                + info.assetName + ReleaseSignatureVerifier.SIGNATURE_SUFFIX + " asset");
        }
        if (info.signatureSize > MAX_SIGNATURE_BYTES) {
            fail(file, "signature asset is " + info.signatureSize + " bytes; refusing");
        }
        byte[] sig;
        try (HttpTransport.Response resp = getAsset(info.signatureUrl, info.assetName + ReleaseSignatureVerifier.SIGNATURE_SUFFIX)) {
            if (resp.status() >= 400) {
                fail(file, "signature download returned HTTP " + resp.status());
            }
            if (resp.contentLength() > MAX_SIGNATURE_BYTES) {
                fail(file, "signature Content-Length " + resp.contentLength() + " exceeds the bound");
            }
            sig = readBounded(new DeadlineInputStream(resp.bodyOrEmpty(), MAX_DOWNLOAD_TIME), MAX_SIGNATURE_BYTES);
        }
        try {
            signatures.verifyOrThrow(file, sig);
        } catch (GeneralSecurityException e) {
            fail(file, "release signature rejected for " + info.assetName + ": " + e.getMessage());
        }
        SourbyLogger.info("auto-updater: Ed25519 release signature verified for " + info.assetName);
    }

    private void fail(Path file, String reason) throws IOException {
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
        throw new IOException(reason);
    }

    private static boolean isValidZip(Path file) {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            return zf.entries().hasMoreElements();
        } catch (IOException e) {
            return false;
        }
    }

    private static @Nullable String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable String normalizeSha256(@Nullable String digest) {
        if (digest == null || digest.isBlank()) return null;
        String d = digest.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("sha256:")) d = d.substring("sha256:".length());
        return d.isBlank() ? null : d;
    }

    private void writePath(Path pathFile, Path value) {
        try {
            Files.createDirectories(pathFile.getParent());
            Files.writeString(pathFile, value.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SourbyLogger.warn("auto-updater: failed to write " + pathFile + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ json helpers

    private static boolean getBool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static @Nullable String getString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
            ? o.get(key).getAsString() : null;
    }

    // ------------------------------------------------------------------ types

    /** Outcome of a single check — returned so an on-demand check (e.g. a command) can report it. */
    public enum Outcome {
        DISABLED, NO_RELEASE, UP_TO_DATE, NOTIFIED,
        /** Downloaded, verified and placed in the stage dir; nothing installs it until {@code /update apply}. */
        STAGED,
        STAGE_FAILED, APPLYING,
        /**
         * Staged and verified, but the swap cannot run here (launch jar unknown, its directory not
         * writable, too little free space); nothing was broadcast or restarted.
         */
        APPLY_FAILED,
        /** A rollback hold is in force ({@link UpdateRollbackGuard}); nothing was checked. */
        HELD,
        /** Inside a GitHub rate-limit backoff window; nothing was checked. */
        RATE_LIMITED,
        /** Newer release announced but it targets another Minecraft version; not staged. */
        MC_VERSION_MISMATCH,
        /**
         * The release is authentic but its embedded SourbyClip launcher declares a protocol or
         * version this server cannot hand over to, or declares none (fails closed); not staged.
         */
        CLIP_INCOMPATIBLE,
        /** Every newer release inspected is in the signed manifests' yank list; nothing announced or staged. */
        YANKED,
        /** A newer release exists but its signed manifest's rollout percentage does not include this server yet. */
        ROLLOUT_DEFERRED,
        /**
         * This build enforces release manifests (release key present) and the newest candidate's
         * manifest is missing, unsigned, tampered, malformed or bound to another release; nothing staged.
         */
        MANIFEST_REJECTED
    }

    /**
     * Safe apply mode. {@code notify} = log + op-notify only (default, safest); {@code stage} =
     * download + verify + place in the stage dir, installed only by {@code /update apply} or by hand;
     * {@code auto} = stage, swap the launch jar and restart; {@code off} = disabled.
     */
    public enum ApplyMode {
        NOTIFY, STAGE, AUTO, OFF;

        static ApplyMode parse(String raw) {
            if (raw == null) return NOTIFY;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "stage" -> STAGE;
                case "auto", "restart" -> AUTO;
                case "off", "disabled", "false" -> OFF;
                default -> NOTIFY; // "notify" and anything unknown -> the safe default.
            };
        }
    }

    private record ReleaseInfo(
        String tagName,
        @Nullable String htmlUrl,
        @Nullable String body,
        String assetName,
        String downloadUrl,
        long declaredSize,
        @Nullable String sha256,
        @Nullable String signatureUrl,
        long signatureSize
    ) {
        /** First non-blank line of the release body, for the notification's one-line notes. */
        @Nullable String notesSummary() {
            if (body == null) return null;
            for (String line : body.split("\\R")) {
                String t = line.replace("#", "").trim();
                if (!t.isBlank()) return t;
            }
            return null;
        }
    }

    /**
     * Config bridge — reads the {@code updater.*} keys through
     * {@link AutoUpdateSettings}'s static fields. Kept as a tiny indirection so
     * {@link SourbyUpdater} never imports the config class field-by-field at every call site and
     * so it degrades to safe defaults if the settings failed to load.
     */
    private static final class Config {
        static String repo()            { return valueOr(AutoUpdateSettings.repo, "YanIanZ/SourbyCraft"); }
        static String channel()         { return AutoUpdateSettings.channel; }
        static String mode()            { return valueOr(AutoUpdateSettings.applyMode, "notify"); }
        static boolean allowPrerelease(){ return AutoUpdateSettings.allowPrerelease; }
        static java.util.List<String> checkTimes() {
            java.util.List<String> t = AutoUpdateSettings.checkTimes;
            return t == null ? java.util.List.of() : t;
        }
        static int checkIntervalMinutes() { return Math.max(0, AutoUpdateSettings.checkIntervalMinutes); }
        static String restartMode()       { return valueOr(AutoUpdateSettings.restartMode, "auto"); }
        static int restartDelaySeconds()  { return Math.max(5, AutoUpdateSettings.restartDelaySeconds); }
        private static String valueOr(String v, String def) { return v == null || v.isBlank() ? def : v.trim(); }
        private Config() {}
    }
}
