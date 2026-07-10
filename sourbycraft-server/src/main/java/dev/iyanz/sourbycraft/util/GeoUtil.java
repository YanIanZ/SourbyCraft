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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Offline geo lookup for {@code /ping}. Resolves a player's IP to city / country
 * using a MaxMind-DB-format {@code .mmdb} read <em>locally</em> — the player IP
 * never leaves the server (privacy requirement).
 *
 * <p>The database is the keyless, free db-ip.com "IP to City Lite" file
 * (CC-BY-4.0). It is lazy-downloaded on first geo use into {@code cache/geoip/}
 * and the reader is kept open for the process lifetime. {@link DatabaseReader}
 * is thread-safe for concurrent lookups, which matters on Folia (no single main
 * thread). If the DB cannot be fetched (offline / 404) every call degrades to
 * {@code null} so {@code /ping} keeps working and just omits the location.
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

    private static final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Guards one-time reader init / download. */
    private static final Object INIT_LOCK = new Object();
    private static volatile DatabaseReader reader;
    /** True once init has been attempted and failed, so we don't retry the download every call. */
    private static volatile boolean initFailed;

    private GeoUtil() {}

    /**
     * Resolve a player's approximate location, or {@code null} if unavailable
     * (local / private IP, DB missing, or address not in the DB). Never throws.
     */
    public static String lookup(Player player) {
        try {
            InetSocketAddress sock = player.getAddress();
            if (sock == null || sock.getAddress() == null) return null;
            InetAddress addr = sock.getAddress();
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                return "Local network";
            }

            String ip = addr.getHostAddress();
            String cached = cache.get(ip);
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
            }
            if (loc.isEmpty()) return null;

            if (cache.size() >= MAX_CACHE) {
                cache.clear(); // simple bounded-size eviction
            }
            cache.put(ip, loc);
            return loc;
        } catch (Exception e) {
            SourbyLogger.warn("Geo lookup failed: " + e.getMessage());
            return null;
        }
    }

    /** Lazy-init the reader; returns {@code null} (and never retries) if the DB can't be obtained. */
    private static DatabaseReader readerOrNull() {
        DatabaseReader r = reader;
        if (r != null) return r;
        if (initFailed) return null;
        synchronized (INIT_LOCK) {
            if (reader != null) return reader;
            if (initFailed) return null;
            try {
                ensureDbPresent();
                reader = new DatabaseReader.Builder(DB_PATH.toFile()).build();
                SourbyLogger.info("GeoIP database ready (" + DB_PATH + ")");
                return reader;
            } catch (Exception e) {
                initFailed = true;
                SourbyLogger.warn("GeoIP unavailable, /ping location disabled: " + e.getMessage());
                return null;
            }
        }
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
        try (InputStream in = new GZIPInputStream(resp.body())) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, DB_PATH, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
