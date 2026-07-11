package dev.iyanz.sourbycraft.security;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in proxy anti-bypass allowlist (F-proxy security).
 *
 * <p>When the server runs behind a proxy in a forwarding mode, a client that reaches the backend
 * <em>directly</em> (skipping the proxy) can spoof its identity / bypass proxy auth. The primary,
 * recommended defense is a <b>firewall</b>: bind the backend port to the proxy IP only. This
 * listener is a soft, defense-in-depth layer for operators who cannot firewall (shared host, no
 * iptables): it rejects any login whose real (un-spoofed) source IP is not in {@code proxy.allowed-ips}.
 *
 * <p><b>Uses {@link PlayerLoginEvent#getRealAddress()}</b> — the true TCP source of the connection,
 * which behind a proxy is the proxy's own IP regardless of any forwarded/spoofed player address. So
 * a direct (non-proxy) connection presents its own IP, which is not in the allowlist, and is kicked.
 *
 * <p><b>Folia safety.</b> {@code PlayerLoginEvent} fires at login, before region assignment — a
 * region-free hook. The handler only reads an {@link InetAddress}, checks set membership, and flips
 * an enum / sets a kick message. No entity, no world, no scheduling. Registered against the internal
 * Minecraft plugin like the other SourbyCraft actuators.
 *
 * <p><b>Opt-in, default OFF.</b> Registering a {@code PlayerLoginEvent} listener trips the server's
 * {@code HorriblePlayerLoginEventHack}, disabling the 1.20.2+ config-phase fast join path and
 * slowing every join — same cost as {@link dev.iyanz.sourbycraft.perf.MaxPlayersBypass}. So this
 * only registers when {@code proxy.anti-bypass-enabled = true} <em>and</em> a proxy mode is active
 * <em>and</em> {@code proxy.allowed-ips} is non-empty. Default off ⇒ no listener ⇒ fast joins.
 * The firewall approach stays the documented primary; this is the explicit opt-in fallback.
 */
public final class ProxyAllowlistListener implements Listener {

    /** Resolved, immutable allowlist of permitted source IPs (canonical host-address strings). */
    private final Set<String> allowedIps;

    private ProxyAllowlistListener(Set<String> allowedIps) {
        this.allowedIps = allowedIps;
    }

    /**
     * Register the allowlist listener — but only when {@code proxy.anti-bypass-enabled=true}, a proxy
     * forwarding mode is active, and {@code proxy.allowed-ips} is non-empty. Returns whether a
     * listener was actually registered. Default OFF: no listener, fast joins kept.
     */
    public static boolean register(Plugin plugin) {
        boolean enabled = SourbyCraftConfig.cfgBool(ProxyForwarding.ANTI_BYPASS_KEY, false);
        if (!enabled) {
            return false; // silent — the primary firewall guidance is logged by ProxyForwarding.validate
        }
        if (!ProxyForwarding.proxyActive()) {
            SourbyLogger.info("proxy: anti-bypass enabled but no proxy mode is active — allowlist"
                + " listener not registered (nothing to protect; direct connections are already the"
                + " only mode).");
            return false;
        }
        Set<String> allow = normalize(SourbyCraftConfig.cfgStringList(ProxyForwarding.ALLOWED_IPS_KEY));
        if (allow.isEmpty()) {
            SourbyLogger.warn("proxy: anti-bypass enabled but proxy.allowed-ips is empty — NOT"
                + " registering the allowlist listener (an empty allowlist would reject every"
                + " connection). Add your proxy IP(s) to proxy.allowed-ips.");
            return false;
        }
        Bukkit.getPluginManager().registerEvents(new ProxyAllowlistListener(allow), plugin);
        SourbyLogger.warn("proxy: anti-bypass allowlist ENABLED (" + allow.size() + " IP(s)) — direct"
            + " (non-proxy) connections will be refused. Note: this registers a PlayerLoginEvent"
            + " listener, which disables the config-phase fast join path (HorriblePlayerLoginEventHack)"
            + " and slows every join. A firewall restricting the backend port to the proxy is the"
            + " lighter-weight primary defense.");
        return true;
    }

    /** Canonicalise the configured IPs to their {@link InetAddress#getHostAddress()} form when parseable. */
    private static Set<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                // Canonicalise literals (e.g. IPv6 shorthand) so equals-comparison is reliable. A
                // literal never triggers a DNS lookup here; a hostname would, but operators list IPs.
                out.add(InetAddress.getByName(t).getHostAddress());
            } catch (Throwable ignored) {
                out.add(t); // keep the raw token; still usable for an exact string match
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.ALLOWED) return; // already denied elsewhere
        // getRealAddress() = the true TCP source (the proxy IP when forwarding), un-spoofable.
        InetAddress real = e.getRealAddress();
        String ip = (real != null) ? real.getHostAddress() : null;
        if (ip != null && allowedIps.contains(ip)) return; // came through an allowlisted proxy — OK
        e.disallow(PlayerLoginEvent.Result.KICK_OTHER,
            Component.text("Direct connections are not permitted — join through the proxy."));
    }
}
