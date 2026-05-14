package dev.iyanz.sourbycraft.util;

import com.google.gson.Gson;
import org.bukkit.entity.Player;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GeoUtil {
    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    public static String lookup(Player player) {
        try {
            String ip = player.getAddress().getAddress().getHostAddress();
            if (ip.startsWith("127.") || ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.")) {
                return "Local network";
            }
            if (cache.containsKey(ip)) return cache.get(ip);

            URI uri = URI.create("http://ip-api.com/json/" + ip + "?fields=city,country,isp");
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
                cache.put(ip, loc);
                return loc;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private GeoUtil() {}
}
