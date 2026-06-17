package dev.iyanz.sourbycraft.util;

import com.google.gson.Gson;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GeoUtil {

    /** Hard cap on cache size to bound memory under churn / IP-rotation. */
    private static final int MAX_CACHE = 1024;

    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    private GeoUtil() {}

    public static String lookup(Player player) {
        try {
            InetSocketAddress sock = player.getAddress();
            if (sock == null || sock.getAddress() == null) return null;
            InetAddress addr = sock.getAddress();
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                return "Local network";
            }
            String ip = addr.getHostAddress();
            String cached = cache.get(ip);
            if (cached != null) return cached;

            URI uri = URI.create("https://ip-api.com/json/" + ip + "?fields=city,country,isp");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "SourbyCraft");

            if (conn.getResponseCode() == 200) {
                Map<String, Object> data = gson.fromJson(new InputStreamReader(conn.getInputStream()), Map.class);
                String city = (String) data.getOrDefault("city", "");
                String country = (String) data.getOrDefault("country", "");
                String isp = (String) data.getOrDefault("isp", "");
                StringBuilder sb = new StringBuilder();
                if (!city.isEmpty()) sb.append(city).append(", ");
                if (!country.isEmpty()) sb.append(country);
                if (!isp.isEmpty()) sb.append(" | ").append(isp);
                String loc = sb.toString();
                if (cache.size() >= MAX_CACHE) {
                    cache.clear(); // simple bounded-size eviction
                }
                cache.put(ip, loc);
                return loc;
            }
        } catch (Exception e) {
            SourbyLogger.warn("Geo lookup failed: " + e.getMessage());
        }
        return null;
    }
}
