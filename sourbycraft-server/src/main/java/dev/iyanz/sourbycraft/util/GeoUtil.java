package dev.iyanz.sourbycraft.util;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.model.CityResponse;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPInputStream;

/**
 * Offline geo lookup for {@code /ping}. Resolves a player's IP to city / country
 * using a MaxMind-DB-format {@code .mmdb} read <em>locally</em> — the player IP
 * never leaves the server (privacy requirement).
 *
 * <p>The database is the keyless, free db-ip.com "IP to City Lite" file
 * (CC-BY-4.0). The first geo use without a local file starts one background download
 * into {@code cache/geoip/} and answers {@code null} at once, so a {@code /ping}
 * never waits for it; lookups after the download open the file, and the reader is
 * kept open for the process lifetime. {@link DatabaseReader} is thread-safe for
 * concurrent lookups, which matters on Folia (no single main thread). A failed
 * download or open is retried after {@link #RETRY_AFTER} rather than never; until
 * then every call degrades to {@code null} so {@code /ping} keeps working and just
 * omits the location.
 */
public final class GeoUtil {

    /** Hard cap on the resolved-string cache to bound memory under IP churn. */
    private static final int MAX_CACHE = 1024;

    /** On-disk location for the decompressed MaxMind-format database. */
    private static final Path DB_PATH = Paths.get("cache", "geoip", "dbip-city-lite.mmdb");

    /**
     * Keyless db-ip.com free "IP to City Lite" download. Monthly file, gzipped,
     * MaxMind-DB format, no license key. {@code %s} = {@code YYYY-MM}. License:
     * CC-BY-4.0 (attribution to db-ip.com — surfaced in the download log line).
     */
    private static final String DB_URL = "https://download.db-ip.com/free/dbip-city-lite-%s.mmdb.gz";

    /**
     * Size limits for the download: the gzipped body and the database written to disk. Generous
     * bounds against an endless or decompression-bomb body, not tuned to the file's real size
     * (not measured here); a body over either limit fails the download like a network error.
     */
    static final long MAX_COMPRESSED_BYTES = 512L * 1024 * 1024;
    static final long MAX_DATABASE_BYTES = 1024L * 1024 * 1024;

    /** IP to location, least recently used evicted past {@link #MAX_CACHE}; every access holds its monitor. */
    private static final Map<String, String> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE;
        }
    };

    /** How long after a failed download or open the next lookup may try again. */
    private static final Duration RETRY_AFTER = Duration.ofHours(1);

    /** Guards reader init and the download state below. */
    private static final Object INIT_LOCK = new Object();
    private static volatile DatabaseReader reader;
    /** A download task is running; lookups answer {@code null} meanwhile. Guarded by {@link #INIT_LOCK}. */
    private static boolean downloading;
    /** Whether a failure is backing off, and until when ({@link System#nanoTime()}). Guarded by {@link #INIT_LOCK}. */
    private static boolean backingOff;
    private static long retryAtNanos;
    /** Set once a per-lookup DB read error has been logged, so a corrupt DB doesn't spam WARN per IP. */
    private static final java.util.concurrent.atomic.AtomicBoolean dbErrorWarned =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private GeoUtil() {}

    /**
     * Resolve a player's approximate location, or {@code null} if unavailable
     * (local / private IP, DB missing, or address not in the DB). Never throws.
     */
    public static String lookup(Player player) {
        return lookup(player.getAddress());
    }

    /** Immutable address captured on the owning entity thread; database I/O stays off that thread. */
    public static String lookup(InetSocketAddress sock) {
        try {
            if (sock == null || sock.getAddress() == null) return null;
            InetAddress addr = sock.getAddress();
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                return "Local network";
            }

            String ip = addr.getHostAddress();
            String cached;
            synchronized (cache) {
                cached = cache.get(ip);
            }
            if (cached != null) return cached;

            DatabaseReader db = readerOrNull();
            if (db == null) return null;

            String loc;
            try {
                CityResponse resp = db.city(addr);
                String city = resp.getCity() != null ? resp.getCity().getName() : null;
                String country = resp.getCountry() != null ? resp.getCountry().getName() : null;
                StringBuilder sb = new StringBuilder();
                if (city != null && !city.isEmpty()) sb.append(city);
                if (country != null && !country.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(country);
                }
                loc = sb.toString();
            } catch (AddressNotFoundException notFound) {
                // IP is valid but absent from the (free/lite) DB — cache the miss cheaply.
                loc = "";
            } catch (Exception dbErr) {
                // Any other per-lookup DB error (corrupt/truncated mmdb -> GeoIp2Exception, an I/O
                // read fault, etc). Previously this fell through to the outer catch, which WARNs
                // once PER lookup — i.e. once for every distinct player IP on every /ping — spamming
                // the console indefinitely on a bad DB file. Log at most once and degrade to "no
                // location" (return null) so /ping keeps working.
                if (dbErrorWarned.compareAndSet(false, true)) {
                    SourbyLogger.warn("GeoIP lookup error (further errors suppressed): " + dbErr.getMessage());
                }
                return null;
            }
            if (loc.isEmpty()) return null;

            // removeEldestEntry drops the least recently used entry past the cap; the monitor keeps
            // the cap exact under concurrent lookups.
            synchronized (cache) {
                cache.put(ip, loc);
            }
            return loc;
        } catch (Exception e) {
            SourbyLogger.warn("Geo lookup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The reader, or {@code null} while there is none. With the database on disk it is opened here,
     * a local read on the caller's I/O thread. Without it, one background download starts and this
     * returns {@code null} at once. After a failed download or open, lookups return {@code null}
     * until {@link #RETRY_AFTER} has passed; the first lookup after that tries again.
     */
    private static DatabaseReader readerOrNull() {
        DatabaseReader r = reader;
        if (r != null) return r;
        synchronized (INIT_LOCK) {
            if (reader != null) return reader;
            if (downloading || backingOff && System.nanoTime() - retryAtNanos < 0) return null;
            backingOff = false;
            try {
                if (!Files.isRegularFile(DB_PATH) || Files.size(DB_PATH) == 0) {
                    startDownload();
                    return null;
                }
                reader = new DatabaseReader.Builder(DB_PATH.toFile()).build();
                SourbyLogger.info("GeoIP database ready (" + DB_PATH + ")");
                return reader;
            } catch (Exception e) {
                backOff("GeoIP database could not be opened: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Under {@link #INIT_LOCK}: hands the download to {@link VirtualExecutor}. None starts once
     * shutdown has begun or startup failed ({@link dev.iyanz.sourbycraft.core.AuroraRuntime#stopping()});
     * a full executor leaves no state behind, so the next lookup tries again.
     */
    private static void startDownload() {
        if (dev.iyanz.sourbycraft.core.AuroraRuntime.stopping()) return;
        downloading = true;
        try {
            VirtualExecutor.run(GeoUtil::downloadInBackground);
        } catch (RejectedExecutionException busy) {
            downloading = false;
        }
    }

    /** On an I/O thread; clears {@link #downloading} on every exit, backing off unless the file arrived. */
    private static void downloadInBackground() {
        boolean downloaded = false;
        String why = "unexpected error";
        try {
            ensureDbPresent();
            downloaded = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            why = "interrupted";
        } catch (Exception e) {
            why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            synchronized (INIT_LOCK) {
                downloading = false;
                if (!downloaded) backOff("GeoIP database download failed: " + why);
            }
        }
    }

    /** Under {@link #INIT_LOCK}: no attempt before {@link #RETRY_AFTER} has passed. */
    private static void backOff(final String why) {
        backingOff = true;
        retryAtNanos = System.nanoTime() + RETRY_AFTER.toNanos();
        SourbyLogger.warn(why + "; /ping shows no location until the next attempt in " + RETRY_AFTER.toMinutes() + " min");
    }

    /** Ensure the decompressed .mmdb exists on disk, downloading + gunzipping it if needed. */
    private static void ensureDbPresent() throws IOException, InterruptedException {
        if (Files.isRegularFile(DB_PATH) && Files.size(DB_PATH) > 0) return;
        Files.createDirectories(DB_PATH.getParent());

        // db-ip publishes a new monthly file around the 1st; early in the month the current
        // month may not be up yet, so fall back to the previous month.
        YearMonth now = YearMonth.now();
        IOException last = null;
        for (YearMonth ym : new YearMonth[]{now, now.minusMonths(1)}) {
            String url = String.format(DB_URL, String.format("%04d-%02d", ym.getYear(), ym.getMonthValue()));
            try {
                download(url);
                SourbyLogger.info("Downloaded GeoIP DB from db-ip.com (CC-BY-4.0, https://db-ip.com): " + url);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no db-ip.com file available");
    }

    /** Stream the gzipped .mmdb from {@code url}, gunzip it to a temp file, then atomically move into place. */
    private static void download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .header("User-Agent", "SourbyCraft")
            .GET()
            .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        Path tmp = Files.createTempFile(DB_PATH.getParent(), "dbip", ".mmdb.tmp");
        try (InputStream body = resp.body();
             InputStream in = new GZIPInputStream(BoundedIo.limited(body, MAX_COMPRESSED_BYTES))) {
            BoundedIo.copy(in, tmp, MAX_DATABASE_BYTES);
            Files.move(tmp, DB_PATH, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Memory-pressure hook: drop the IP->location cache; entries rebuild on next lookup. */
    public static void trimForMemoryPressure() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
