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
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * Advanced SourbyCraft auto-updater (F1-8) — targets SourbyCraft's own GitHub releases, replacing
 * the archived upstream updater the Folia base shipped (which pointed at a now-dead feed).
 *
 * <p>Design (all knobs live in the unified operator config under {@code misc.auto_update.*}, wired
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
 *   <li><b>Verify.</b> Before staging, the downloaded jar is validated: byte size &gt; 0, matches
 *       the asset's declared size when present, SHA-256 equals the release asset {@code digest}
 *       when GitHub exposes one, and it always must open as a valid ZIP/JAR. A corrupt/partial
 *       download is deleted and never staged.</li>
 *   <li><b>Safe apply modes.</b> {@code notify} (default) logs + op-notifies only; {@code stage}
 *       additionally downloads+verifies and places the jar in {@code auto_update/sourbycraft} plus
 *       writes {@code auto_update/core.path} for a launcher swap on the NEXT restart; {@code off}
 *       disables checks. The running jar is NEVER hot-swapped.</li>
 *   <li><b>Folia-safe.</b> Periodic checks run on {@link Bukkit#getAsyncScheduler()} (never the
 *       Bukkit global scheduler). Network + disk I/O happen off the region threads.</li>
 * </ol>
 *
 * <p>Every network path is defensive: a 404 (no matching release yet), a rate-limit, a parse error
 * or an offline host is logged at INFO/WARN and the check simply ends — it can never crash boot or
 * a scheduled tick.
 */
public final class SourbyUpdater {

    private static final Gson GSON = new Gson();
    private static final String GITHUB_API = "https://api.github.com/repos/";
    private static final long DAILY_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final Path autoUpdateDir = Path.of("auto_update");
    private final Path stageDir      = autoUpdateDir.resolve("sourbycraft");
    private final Path corePathFile  = autoUpdateDir.resolve("core.path");
    private final Path latestPathFile = stageDir.resolve("latest.path");

    private final AtomicBoolean checkRunning = new AtomicBoolean(false);
    private volatile boolean started = false;

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
            SourbyLogger.warn("auto-updater enabled but misc.auto_update.check_times is empty; no checks scheduled.");
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
                Bukkit.getAsyncScheduler().runAtFixedRate(
                    owner,
                    task -> checkSafely(),
                    Math.max(1L, delay.toMillis()),
                    DAILY_MILLIS,
                    TimeUnit.MILLISECONDS
                );
                scheduled++;
            } catch (Exception e) {
                SourbyLogger.warn("auto-updater: ignoring illegal check time '" + time + "'");
            }
        }

        // Notify permission holders who join AFTER an update was detected.
        try {
            Bukkit.getPluginManager().registerEvents(new UpdateNotifier.JoinListener(), owner);
        } catch (Throwable t) {
            SourbyLogger.warn("auto-updater: could not register the join-notify listener: " + t.getMessage());
        }

        // One-shot check shortly after boot so a release published while the server was down is
        // picked up within minutes, not at the next daily slot.
        Bukkit.getAsyncScheduler().runDelayed(owner, task -> checkSafely(), 2, TimeUnit.MINUTES);

        int intervalMin = Config.checkIntervalMinutes();
        if (intervalMin > 0) {
            Bukkit.getAsyncScheduler().runAtFixedRate(
                owner,
                task -> checkSafely(),
                TimeUnit.MINUTES.toMillis(intervalMin),
                TimeUnit.MINUTES.toMillis(intervalMin),
                TimeUnit.MILLISECONDS
            );
        }

        started = true;
        SourbyLogger.info("auto-updater started: repo=" + Config.repo()
            + " channel=" + resolveChannel().suffix()
            + " mode=" + mode.name().toLowerCase(Locale.ROOT)
            + " times=" + times
            + (Config.checkIntervalMinutes() > 0 ? " interval=" + Config.checkIntervalMinutes() + "m" : "")
            + " (AsyncScheduler)");
    }

    /** Cancel any scheduled checks. */
    public synchronized void stop() {
        if (started) {
            Plugin owner = MinecraftInternalPlugin.INSTANCE;
            if (owner != null) {
                try {
                    Bukkit.getAsyncScheduler().cancelTasks(owner);
                } catch (Throwable ignored) { /* scheduler may not be up during early teardown */ }
            }
        }
        started = false;
        checkRunning.set(false);
    }

    // ------------------------------------------------------------------ check

    private void checkSafely() {
        if (!checkRunning.compareAndSet(false, true)) {
            SourbyLogger.info("auto-updater: a check is already running, skipping.");
            return;
        }
        try {
            check();
        } catch (Throwable t) {
            SourbyLogger.warn("auto-updater check failed (non-fatal): " + t.getMessage());
        } finally {
            checkRunning.set(false);
        }
        // Keep the auto-provisioned Via plugins current on the same cadence (own try/catch).
        try {
            ViaAutoUpdate.check();
        } catch (Throwable t) {
            SourbyLogger.warn("via-updater failed (non-fatal): " + t.getMessage());
        }
    }

    /** Run one channel-aware update check with the configured apply mode. */
    public Outcome check() {
        return check(null);
    }

    /**
     * Run one check; {@code overrideMode} (e.g. from {@code /update apply}) wins over the
     * configured mode, letting an operator force a download+apply on a notify-configured server.
     */
    public Outcome check(@Nullable ApplyMode overrideMode) {
        ApplyMode mode = overrideMode != null ? overrideMode : ApplyMode.parse(Config.mode());
        if (mode == ApplyMode.OFF) return Outcome.DISABLED;

        String currentVersion = BuildInfo.load().version();
        UpdateChannel channel = resolveChannel();

        ReleaseInfo latest = fetchLatestForChannel(channel);
        if (latest == null) {
            SourbyLogger.info("auto-updater: no matching " + channel.suffix()
                + " release found for repo " + Config.repo() + " (up to date, or none published yet).");
            UpdateNotifier.clearPending();
            return Outcome.NO_RELEASE;
        }

        if (!isNewerThanRunning(latest.tagName, currentVersion)) {
            SourbyLogger.info("auto-updater: already up to date on channel "
                + channel.suffix() + " (current " + currentVersion + " build " + ownBuildNumber()
                + ", latest " + latest.tagName + ").");
            UpdateNotifier.clearPending();
            return Outcome.UP_TO_DATE;
        }
        String appliedTag = readApplied();
        if (latest.tagName.equals(appliedTag)) {
            // Already swapped to this tag (waiting for/after restart, or a pre-r6 jar whose build
            // number was not stamped from the release counter). Never re-download or re-restart.
            SourbyLogger.info("auto-updater: " + latest.tagName + " already applied; nothing to do.");
            return Outcome.UP_TO_DATE;
        }

        // An update exists. notify + (optionally) stage.
        UpdateNotifier.announce(currentVersion, latest.tagName, channel, latest.htmlUrl, latest.notesSummary());

        if (mode == ApplyMode.NOTIFY) {
            return Outcome.NOTIFIED;
        }

        // stage/auto: download + verify + place in the stage dir.
        try {
            Path staged = downloadAndStage(latest);
            writePath(corePathFile, staged);
            writePath(latestPathFile, staged);
            SourbyLogger.info("auto-updater: staged " + latest.tagName + " at "
                + staged.toAbsolutePath() + " and updated auto_update/core.path."
                + (mode == ApplyMode.AUTO ? "" : " Restart to apply."));
            if (mode == ApplyMode.AUTO) {
                UpdateApplier.scheduleApplyAndRestart(latest.tagName, staged,
                    Config.restartDelaySeconds(), Config.restartMode());
                return Outcome.APPLYING;
            }
            return Outcome.STAGED;
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: failed to stage " + latest.tagName + ": " + e.getMessage());
            return Outcome.STAGE_FAILED;
        }
    }

    // ------------------------------------------------------------------ release-number ordering

    /**
     * Newer-ness for our per-release tags: cores compare first ({@code 26.11 > 26.2}); equal cores
     * fall back to the tag's release number {@code rN} versus THIS jar's stamped build number
     * ({@code Nf} — CI stamps both from the same counter since r6). Without this, every
     * {@code v26.2-rN} tag compares equal to the running {@code 26.2-REL} and no update is ever
     * taken on the same Minecraft version line.
     */
    static boolean isNewerThanRunning(String tag, String currentVersion) {
        int coreCmp = SemVer.compare(coreOf(tag), coreOf(currentVersion));
        if (coreCmp != 0) return coreCmp > 0;
        int tagRel = releaseNumberOf(tag);
        int own = ownBuildNumber();
        return tagRel > 0 && own > 0 && tagRel > own;
    }

    /**
     * Order two release TAGS: numeric core first, then the {@code -rN} release counter. NOT plain
     * {@link SemVer#compare}, which tokenises {@code "26.2-r10"} into {@code [26,2,"r10"]} and then
     * compares {@code "r10"} vs {@code "r9"} LEXICALLY — {@code "r10" < "r9"} — so it ranks r10
     * BELOW r9. That made {@code fetchLatestForChannel} keep picking r9 as "latest" and the
     * updater got stuck one release behind forever.
     */
    static int compareTags(String a, String b) {
        int coreCmp = SemVer.compare(coreOf(a), coreOf(b));
        if (coreCmp != 0) return coreCmp;
        return Integer.compare(releaseNumberOf(a), releaseNumberOf(b));
    }

    /** Version core with any {@code -rN} release counter AND channel suffix stripped. */
    private static String coreOf(String version) {
        String v = SemVer.stripChannel(version);
        return RELEASE_NUM.matcher(v).replaceFirst("");
    }

    private static final java.util.regex.Pattern RELEASE_NUM =
        java.util.regex.Pattern.compile("-r(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** {@code v26.2-r7} -> 7; -1 when the tag has no release counter. */
    static int releaseNumberOf(String tag) {
        if (tag == null) return -1;
        var m = RELEASE_NUM.matcher(SemVer.stripChannel(tag));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** This jar's stamped build number ({@code "7c"} -> 7); -1 when unknown/dev. Suffix-agnostic
     *  (reads only the leading digits), so it doesn't care whether the platform suffix is "c"
     *  (Canvas, current) or the archived "f" (Folia). */
    static int ownBuildNumber() {
        try {
            String b = BuildInfo.load().build();
            if (b == null) return -1;
            int i = 0;
            while (i < b.length() && Character.isDigit(b.charAt(i))) i++;
            return i > 0 ? Integer.parseInt(b.substring(0, i)) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private @Nullable String readApplied() {
        try {
            Path f = autoUpdateDir.resolve("applied.tag");
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private UpdateChannel resolveChannel() {
        // Operator override wins; else derive from this build's own version suffix.
        UpdateChannel forced = UpdateChannel.parseConfig(Config.channel());
        if (forced != null) return forced;
        return UpdateChannel.parseFromVersion(BuildInfo.load().version());
    }

    // ------------------------------------------------------------------ GitHub

    private @Nullable ReleaseInfo fetchLatestForChannel(UpdateChannel channel) {
        JsonArray releases = requestArray(GITHUB_API + Config.repo() + "/releases?per_page=100");
        if (releases == null) return null;

        ReleaseInfo best = null;
        for (JsonElement el : releases) {
            if (!el.isJsonObject()) continue;
            ReleaseInfo info = parseRelease(el.getAsJsonObject(), channel);
            if (info == null) continue;
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

        for (JsonElement ae : assets) {
            if (!ae.isJsonObject()) continue;
            JsonObject asset = ae.getAsJsonObject();
            String name = getString(asset, "name");
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;

            String url = getString(asset, "browser_download_url");
            if (url == null) continue;
            long size = asset.has("size") && asset.get("size").isJsonPrimitive()
                ? asset.get("size").getAsLong() : -1L;
            String sha256 = normalizeSha256(getString(asset, "digest"));

            return new ReleaseInfo(
                tag,
                getString(rel, "html_url"),
                getString(rel, "body"),
                name, url, size, sha256
            );
        }
        return null;
    }

    // ------------------------------------------------------------------ download + verify + stage

    private Path downloadAndStage(ReleaseInfo info) throws IOException {
        Files.createDirectories(stageDir);
        Path temp   = stageDir.resolve(info.assetName + ".part");
        Path target = stageDir.resolve(info.assetName);

        Files.deleteIfExists(temp);
        try (InputStream in = openConnection(info.downloadUrl).getInputStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }

        verifyOrThrow(temp, info);

        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * Verify a downloaded jar before it is staged for execution. Order: non-empty -> declared size
     * (when known) -> <b>mandatory SHA-256</b> against the release asset digest -> valid-ZIP/JAR open.
     * A staged jar is executed on the next restart, so integrity verification is REQUIRED: an asset
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

    // ------------------------------------------------------------------ http helpers

    private @Nullable JsonArray requestArray(String url) {
        JsonElement el = requestJson(url);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private @Nullable JsonElement requestJson(String url) {
        try {
            HttpURLConnection conn = openConnection(url);
            int code = conn.getResponseCode();
            if (code == 404) {
                SourbyLogger.info("auto-updater: repo/releases not found (404) for " + url
                    + " — no releases yet, or check misc.auto_update.repo.");
                return null;
            }
            if (code >= 400) {
                SourbyLogger.warn("auto-updater: GitHub API returned " + code + " for " + url);
                return null;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return GSON.fromJson(r, JsonElement.class);
            }
        } catch (JsonSyntaxException e) {
            SourbyLogger.warn("auto-updater: failed to parse GitHub response from " + url);
            return null;
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: request to " + url + " failed: " + e.getMessage());
            return null;
        }
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException("bad url: " + url, e);
        }
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "SourbyCraft-AutoUpdate");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static boolean getBool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static @Nullable String getString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
            ? o.get(key).getAsString() : null;
    }

    // ------------------------------------------------------------------ types

    /** Outcome of a single check — returned so an on-demand check (e.g. a command) can report it. */
    public enum Outcome { DISABLED, NO_RELEASE, UP_TO_DATE, NOTIFIED, STAGED, STAGE_FAILED, APPLYING }

    /**
     * Safe apply mode. {@code notify} = log + op-notify only (default, safest); {@code stage} =
     * download + verify + place in stage dir for a next-restart swap; {@code off} = disabled.
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
        @Nullable String sha256
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
     * Config bridge — reads the {@code misc.auto_update.*} keys through
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
