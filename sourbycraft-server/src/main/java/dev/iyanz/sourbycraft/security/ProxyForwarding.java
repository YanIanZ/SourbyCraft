package dev.iyanz.sourbycraft.security;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.text.format.TextColor;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SourbyCraft proxy-forwarding config surface (F-proxy).
 *
 * <p>Paper/Folia already <b>implements</b> both forwarding schemes natively — this class does
 * <em>not</em> reimplement forwarding. It gives operators one clean SourbyCraft config surface
 * ({@code proxy.*} in the unified TOML), applies it to Paper's own settings at boot when a mode
 * is chosen, validates the effective configuration, and logs the detected mode as one hex line.
 *
 * <p><b>Two native schemes:</b>
 * <ul>
 *   <li><b>Velocity modern forwarding</b> — secure, secret-based. Native keys:
 *       {@code paper-global.yml} {@code proxies.velocity.{enabled, online-mode, secret}}.</li>
 *   <li><b>Legacy BungeeCord forwarding</b> — used by BungeeCord, Waterfall, and the BungeeCord
 *       forks XCord + FlameCord. Native keys: {@code spigot.yml} {@code settings.bungeecord: true}
 *       plus {@code paper-global.yml} {@code proxies.bungee-cord.online-mode}.</li>
 * </ul>
 *
 * <p><b>Apply-vs-document.</b> This runs from the authored post-config hook
 * ({@link me.earthme.luminol.commands.CommandRegister#register()}), which fires from
 * {@code DedicatedServer#initServer} at the {@code ConfigManager.loadConfigFiles()} point —
 * strictly <em>before</em> {@code startTcpServerListener(bindAddress)}. Paper's
 * {@code GlobalConfiguration} and {@code SpigotConfig} are already loaded, and
 * {@code SpigotConfig.bungee} / {@code proxies.velocity.enabled} are only read again during the
 * client handshake (after bind). So auto-applying the SourbyCraft {@code proxy.mode} onto those
 * fields here is Folia-safe: it wins before the network binds and before any handshake reads them.
 * When {@code proxy.mode = none} (default) nothing is applied — the class only <em>detects</em> the
 * operator's native config, validates it, and logs it.
 *
 * <p><b>Anti-bypass.</b> The recommended, primary defense is a firewall: bind the backend port to
 * the proxy IP only, so no client can reach the backend directly and spoof its identity. A soft,
 * opt-in {@code proxy.allowed-ips} allowlist listener is available for defense-in-depth but is
 * <b>OFF by default</b> — like {@link dev.iyanz.sourbycraft.perf.MaxPlayersBypass}, registering a
 * {@code PlayerLoginEvent} listener trips the {@code HorriblePlayerLoginEventHack} and slows every
 * join, so it only registers when {@code proxy.anti-bypass-enabled = true}.
 *
 * <p>Read-only + advisory by default; never blocks boot on failure.
 */
public final class ProxyForwarding {

    private ProxyForwarding() {}

    /** {@code proxy.mode} values. */
    public enum Mode { NONE, VELOCITY, BUNGEECORD }

    // --- Config keys (unified TOML). ---
    public static final String MODE_KEY               = "proxy.mode";
    public static final String VELOCITY_SECRET_KEY    = "proxy.velocity.secret";
    public static final String VELOCITY_ONLINE_KEY    = "proxy.velocity.online-mode";
    public static final String BUNGEE_ONLINE_KEY      = "proxy.bungeecord.online-mode";
    public static final String ANTI_BYPASS_KEY        = "proxy.anti-bypass-enabled";
    public static final String ALLOWED_IPS_KEY        = "proxy.allowed-ips";

    /** The effective mode resolved at boot, exposed for the allowlist listener guard. */
    private static volatile Mode effectiveMode = Mode.NONE;

    /** UTF-8 stdout for the hex line (matches {@code StartupBanner}). */
    private static final PrintStream UTF8_OUT = utf8ConsoleStream();

    private static PrintStream utf8ConsoleStream() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return System.out;
        }
    }

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";

    private static String fg(TextColor c) {
        return ESC + "[38;2;" + c.red() + ";" + c.green() + ";" + c.blue() + "m";
    }

    /**
     * Resolve the SourbyCraft {@code proxy.*} config, apply it to Paper's native settings when a
     * mode is chosen (safe — runs before the network binds), validate the effective config, and log
     * the active mode as one hex line plus any misconfig warnings.
     *
     * <p>Invoked once from {@link me.earthme.luminol.commands.CommandRegister#register()} after the
     * unified TOML is loaded. Never throws — advisory only.
     */
    public static void run() {
        try {
            io.papermc.paper.configuration.GlobalConfiguration global =
                io.papermc.paper.configuration.GlobalConfiguration.get();

            String rawMode = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgGet(MODE_KEY, "none");
            Mode mode = parseMode(rawMode);

            // --- Apply the chosen mode to Paper's native settings (pre-bind, Folia-safe). ---
            // proxy.mode = none => never override; we only detect + validate the operator's own
            // paper-global.yml / spigot.yml below.
            if (mode == Mode.VELOCITY) {
                String secret = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgGet(VELOCITY_SECRET_KEY, "");
                boolean onlineMode = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgBool(VELOCITY_ONLINE_KEY, true);
                // Environment override still wins (PAPER_VELOCITY_SECRET) — mirror Paper: only push a
                // TOML secret when the operator actually supplied one (never blank out an env secret).
                global.proxies.velocity.enabled = true;
                global.proxies.velocity.onlineMode = onlineMode;
                if (secret != null && !secret.isEmpty()) {
                    global.proxies.velocity.secret = secret;
                }
                // Velocity + legacy bungee are mutually exclusive; make the choice authoritative.
                org.spigotmc.SpigotConfig.bungee = false;
            } else if (mode == Mode.BUNGEECORD) {
                boolean onlineMode = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgBool(BUNGEE_ONLINE_KEY, true);
                org.spigotmc.SpigotConfig.bungee = true;
                global.proxies.bungeeCord.onlineMode = onlineMode;
                global.proxies.velocity.enabled = false;
            }

            // --- Detect the effective state (whatever is now set — applied or operator-native). ---
            boolean velocityOn = global.proxies.velocity.enabled;
            boolean bungeeOn = org.spigotmc.SpigotConfig.bungee;
            effectiveMode = velocityOn ? Mode.VELOCITY : (bungeeOn ? Mode.BUNGEECORD : Mode.NONE);

            // --- One clean hex line for the active mode. ---
            logHexModeLine(effectiveMode);

            // --- Misconfig validation (warn-only; never blocks boot). ---
            validate(global, velocityOn, bungeeOn);
        } catch (Throwable t) {
            // Advisory is non-fatal; a Paper internals shift must never abort boot.
            SourbyLogger.warn("ProxyForwarding: config apply/validate failed: " + t.getMessage());
        }
    }

    private static Mode parseMode(String raw) {
        if (raw == null) return Mode.NONE;
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "velocity":   return Mode.VELOCITY;
            case "bungeecord":
            case "bungee":     return Mode.BUNGEECORD;
            case "none":
            case "":           return Mode.NONE;
            default:
                SourbyLogger.warn("proxy.mode='" + raw + "' unrecognised (expected none|velocity|bungeecord)"
                    + " — treating as 'none' (direct); the server will still honour any native"
                    + " paper-global.yml / spigot.yml forwarding config.");
                return Mode.NONE;
        }
    }

    private static void logHexModeLine(Mode mode) {
        final String label;
        final TextColor color;
        switch (mode) {
            case VELOCITY:
                label = "velocity (modern forwarding)";
                color = SourbyCraftColors.SUCCESS;
                break;
            case BUNGEECORD:
                label = "bungeecord (legacy forwarding)";
                color = SourbyCraftColors.INFO;
                break;
            default:
                label = "direct (no proxy)";
                color = SourbyCraftColors.DIM;
                break;
        }
        try {
            System.out.flush();
            UTF8_OUT.println(fg(SourbyCraftColors.PRIMARY) + "   [SourbyCraft] proxy: "
                + fg(color) + label + RESET);
            UTF8_OUT.flush();
        } catch (Throwable t) {
            // Fall back to the plain logger if the raw console stream is unavailable.
            SourbyLogger.info("proxy: " + label);
        }
    }

    /** Warn on the common proxy-forwarding misconfigurations. */
    private static void validate(io.papermc.paper.configuration.GlobalConfiguration global,
                                 boolean velocityOn, boolean bungeeOn) {
        // 1. Velocity enabled but no secret — Paper will DISABLE velocity at handshake init, silently
        //    dropping every proxied login. Surface it loudly here (env secret still counts).
        if (velocityOn) {
            String secret = global.proxies.velocity.secret;
            String envSecret = System.getenv("PAPER_VELOCITY_SECRET");
            boolean hasSecret = (secret != null && !secret.isEmpty())
                || (envSecret != null && !envSecret.isEmpty());
            if (!hasSecret) {
                SourbyLogger.warn("proxy: Velocity modern forwarding is ENABLED but no secret is set"
                    + " (proxy.velocity.secret / paper-global.yml proxies.velocity.secret /"
                    + " PAPER_VELOCITY_SECRET env are all empty). Paper will DISABLE velocity and drop"
                    + " every proxied login. Set the SAME secret configured in your Velocity"
                    + " forwarding.secret file.");
            }
        }

        // 2. Both velocity and legacy bungee enabled — undefined; pick one.
        if (velocityOn && bungeeOn) {
            SourbyLogger.warn("proxy: BOTH Velocity modern forwarding AND legacy BungeeCord forwarding"
                + " are enabled. These are mutually exclusive — the handshake path is undefined."
                + " Disable one (set proxy.mode=velocity or =bungeecord, or fix"
                + " paper-global.yml/spigot.yml).");
        }

        // 3. A proxy mode active + backend still in online-mode=true — the backend will try to
        //    Mojang-auth the (already-proxy-authed) player, which fails behind a proxy.
        if (velocityOn || bungeeOn) {
            boolean backendOnline = org.bukkit.Bukkit.getOnlineMode();
            if (backendOnline) {
                SourbyLogger.warn("proxy: a proxy forwarding mode is active but the backend is in"
                    + " online-mode=true (server.properties). Behind a proxy the backend must run"
                    + " online-mode=false and let the PROXY authenticate. IMPORTANT: with online-mode"
                    + " off, you MUST restrict the backend port to the proxy (firewall / allowed-ips)"
                    + " or anyone can connect as any username.");
            }
        }

        // 4. Anti-bypass reminder when a mode is active (firewall is the primary defense).
        if (velocityOn || bungeeOn) {
            boolean antiBypass = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgBool(ANTI_BYPASS_KEY, false);
            List<String> allow = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgStringList(ALLOWED_IPS_KEY);
            if (!antiBypass) {
                SourbyLogger.info("proxy: anti-bypass allowlist listener is OFF (recommended: firewall"
                    + " the backend port to your proxy IP so direct connections cannot reach it)."
                    + " To also reject non-proxy IPs at login, set proxy.anti-bypass-enabled=true and"
                    + " list proxy.allowed-ips (costs the config-phase fast join path).");
            } else if (allow.isEmpty()) {
                SourbyLogger.warn("proxy: anti-bypass is enabled but proxy.allowed-ips is EMPTY —"
                    + " with an empty allowlist EVERY connection would be rejected. Populate"
                    + " proxy.allowed-ips with your proxy IP(s), or set proxy.anti-bypass-enabled=false"
                    + " and rely on a firewall.");
            }
        }
    }

    /** True when the effective mode resolved at boot is a proxy mode (velocity or bungeecord). */
    public static boolean proxyActive() {
        return effectiveMode != Mode.NONE;
    }
}
