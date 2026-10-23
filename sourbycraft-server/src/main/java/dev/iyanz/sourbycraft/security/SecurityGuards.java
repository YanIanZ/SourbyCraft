package dev.iyanz.sourbycraft.security;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.bukkit.configuration.file.YamlConfiguration;

/** Network decode guards. No world access, executors, player references or unbounded peer map. */
public final class SecurityGuards {
    public enum Limit {
        NBT_BYTES("crash-prevention.nbt", "max-bytes", 2097152),
        NBT_DEPTH("crash-prevention.nbt", "max-depth", 64),
        NBT_STRING("crash-prevention.nbt", "max-string-length", 4096),
        NBT_LIST("crash-prevention.nbt", "max-list-size", 65536),
        SIGN_LINE("crash-prevention.sign", "max-line-length", 256),
        SIGN_TOTAL("crash-prevention.sign", "max-total-chars", 1024),
        ANVIL_NAME("crash-prevention.anvil", "max-item-name-length", 128),
        RECIPE_BYTES("crash-prevention.recipe-book", "max-packet-size", 20480),
        CREATIVE_BYTES("crash-prevention.creative-item", "max-nbt-size", 16384),
        BOOK_CHARS("packet-guard.book-edit", "max-total-chars", 65536),
        PAYLOAD_BYTES("packet-guard.custom-payload", "max-bytes", 8192),
        CONTAINER_RATE("packet-guard.container-click", "max-per-tick", 256),
        RECIPE_RATE("packet-guard.recipe-book", "max-per-tick", 16),
        CONNECTION_RATE("packet-guard.connection", "max-per-second", 1000),
        CONNECTION_BYTES("packet-guard.connection", "max-bytes-per-second", 8388608),
        ADMISSION_CONNECTIONS("connection-guard.global", "max-connections-per-second", 1000),
        ADMISSION_LOGINS("connection-guard.global", "max-login-starts-per-second", 100),
        ADDRESS_CONNECTIONS("connection-guard.per-address", "max-connections-per-second", 20),
        ADDRESS_LOGINS("connection-guard.per-address", "max-login-starts-per-second", 5);

        final String group;
        final String key;
        final long fallback;
        Limit(String group, String key, long fallback) { this.group = group; this.key = group + "." + key; this.fallback = fallback; }
    }

    public enum Mode { OFF, LOG, KICK }
    private record Rule(long maximum, Mode mode) {}
    /**
     * Native forwarding settings, read once when the TCP listener starts (RESTART_REQUIRED, like
     * Paper's own forwarding options). {@code transport} = the TCP peer at accept time is a proxy
     * (BungeeCord, Velocity or PROXY protocol); {@code lateLogin} = Velocity modern forwarding,
     * whose player address is only known after Hello.
     */
    record ProxyPolicy(boolean bungee, boolean velocity, boolean proxyProtocol) {
        static final ProxyPolicy DIRECT = new ProxyPolicy(false, false, false);
        boolean transport() { return bungee || velocity || proxyProtocol; }
        boolean lateLogin() { return velocity; }
    }
    private record Settings(Map<Limit, Rule> rules, int ipv6PrefixLength, ProxyPolicy proxy) {}
    private static final String IPV6_PREFIX_KEY = "connection-guard.per-address.ipv6-prefix-length";
    public static final class Rejected extends io.netty.handler.codec.DecoderException {
        Rejected(Limit limit) { super("SourbyCraft security limit: " + limit.key); }
    }
    public static final SecurityGuards INSTANCE = new SecurityGuards(SourbyLogger::warn, System::nanoTime);
    private static final AttributeKey<ConnectionState> STATE = AttributeKey.valueOf("sourbycraft.security.state");
    private static final long LOG_INTERVAL = 30_000_000_000L;
    private final Consumer<String> logger;
    private final LongSupplier clock;
    private final AtomicLongArray counters = new AtomicLongArray(Limit.values().length);
    private final AtomicLongArray nextLog = new AtomicLongArray(Limit.values().length);
    private final AdmissionRateLimiter admissions = new AdmissionRateLimiter(8192);
    private final ThreadLocal<Scope> active = new ThreadLocal<>();
    private volatile Settings settings = new Settings(defaults(), 64, ProxyPolicy.DIRECT);

    SecurityGuards(Consumer<String> logger, LongSupplier clock) {
        this.logger = logger;
        this.clock = clock;
        for (Limit limit : Limit.values()) nextLog.set(limit.ordinal(), Long.MIN_VALUE);
    }

    private static Map<Limit, Rule> defaults() {
        var result = new EnumMap<Limit, Rule>(Limit.class);
        for (Limit limit : Limit.values()) result.put(limit, new Rule(limit.fallback, Mode.LOG));
        return Map.copyOf(result);
    }

    public static Set<String> consumedKeys() {
        var result = new HashSet<String>();
        for (Limit limit : Limit.values()) { result.add(limit.key); result.add(limit.group + ".mode"); }
        result.add(IPV6_PREFIX_KEY);
        return Set.copyOf(result);
    }

    /** Boot and /sourbycraft reload. Publish a complete immutable set only after validation. */
    public synchronized void load(Path file) throws Exception {
        var yaml = new YamlConfiguration();
        if (Files.exists(file)) yaml.load(file.toFile());
        var updated = new EnumMap<Limit, Rule>(Limit.class);
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key) && !consumedKeys().contains(key)) throw new IOException("Unknown security key: " + key);
        }
        for (Limit limit : Limit.values()) {
            Object configured = yaml.get(limit.key);
            if (configured != null && !(configured instanceof Number)) throw new IOException("Expected integer: " + limit.key);
            long maximum = configured == null ? limit.fallback : ((Number) configured).longValue();
            if (maximum <= 0 || (configured != null && ((Number) configured).doubleValue() != maximum)) {
                throw new IOException("Expected positive integer: " + limit.key);
            }
            Object configuredMode = yaml.get(limit.group + ".mode", "LOG");
            // SnakeYAML's YAML 1.1 scalar resolver reads an unquoted OFF as Boolean.FALSE.
            Mode mode;
            if (Boolean.FALSE.equals(configuredMode)) mode = Mode.OFF;
            else if (configuredMode instanceof String text) mode = Mode.valueOf(text.toUpperCase(Locale.ROOT));
            else throw new IOException("Expected LOG, KICK or OFF: " + limit.group + ".mode");
            updated.put(limit, new Rule(maximum, mode));
            yaml.set(limit.key, maximum);
            yaml.set(limit.group + ".mode", mode.name());
        }
        Object prefix = yaml.get(IPV6_PREFIX_KEY, 64);
        if (!(prefix instanceof Number number) || number.doubleValue() != number.intValue()
            || number.intValue() < 1 || number.intValue() > 128) throw new IOException("Expected integer 1..128: " + IPV6_PREFIX_KEY);
        yaml.set(IPV6_PREFIX_KEY, number.intValue());
        if (!Files.exists(file)) Files.writeString(file, seededDocument()); // never rewrite an operator file on reload
        settings = new Settings(Map.copyOf(updated), number.intValue(), settings.proxy());
    }

    // ------------------------------------------------------------------------- seeded document

    /** The header of a freshly seeded {@code sourbycraft-security.yml}. */
    static final List<String> HEADER = List.of(
        "SourbyCraft security limits (sourbycraft-security.yml) - Build 49 layout.",
        "",
        "Network decode guards (SEC-1) and connection admission (SEC-11). Keep existing values when updating the server.",
        "Every group has a mode: LOG counts and logs (rate-limited), KICK disconnects the offending connection, OFF disables",
        "the check. Sizes are bytes unless the key names a character count; depth and list sizes are counts.",
        "Lifecycle: LIVE - every limit, mode and ipv6-prefix-length applies on /sourbycraft reload. The proxy detection of",
        "connection-guard (BungeeCord, Velocity, PROXY protocol) is read when the TCP listener starts: RESTART_REQUIRED.",
        "The defaults are stated beside each group; deleting this file restores all of them at the next boot.",
        "These limits are separate from the Aurora services (sourbycraft_config/aurora.yml) and the region engine",
        "(config/aurora-server.yml). Reference: docs/development/SECURITY-GUARDS.md, docs/AURORA-CONFIG.md.");

    /** Comments above each group, in file order. */
    static final Map<String, List<String>> GROUP_DOCS = groupDocs();

    private static Map<String, List<String>> groupDocs() {
        final Map<String, List<String>> docs = new java.util.LinkedHashMap<>();
        docs.put("crash-prevention", List.of("Payload sanity limits for decoders that could allocate without bound or recurse."));
        docs.put("crash-prevention.nbt", List.of("Item and entity NBT sent by the client. Defaults: 2097152 bytes, depth 64, strings 4096 chars, lists 65536 entries."));
        docs.put("crash-prevention.sign", List.of("Sign edits. Defaults: 256 chars per line, 1024 chars in total."));
        docs.put("crash-prevention.anvil", List.of("Anvil renames. Default: 128 chars."));
        docs.put("crash-prevention.recipe-book", List.of("Encoded incoming recipe-book packet bytes; native 26.x packet codecs are already bounded. Default: 20480."));
        docs.put("crash-prevention.creative-item", List.of("Encoded creative item/component bytes. Default: 16384. LOG observes; KICK disconnects rather than silently",
            "dropping or clamping."));
        docs.put("packet-guard", List.of("Packet observation limits. max-per-tick counts operations per monotonic 50 ms bucket, not bytes or region ticks."));
        docs.put("packet-guard.book-edit", List.of("Book edits. Default: 65536 chars in total."));
        docs.put("packet-guard.custom-payload", List.of("Plugin-message payloads. Default: 8192 bytes."));
        docs.put("packet-guard.container-click", List.of("A drag sends start + one packet per slot + end; the default of 256 per tick covers that burst before it counts as spam."));
        docs.put("packet-guard.recipe-book", List.of("Recipe-book interactions. Default: 16 per tick."));
        docs.put("packet-guard.connection", List.of("Per connection: packets per second (default 1000) and bytes per second (default 8388608)."));
        docs.put("connection-guard", List.of("Accepted TCP sockets and initial Login Hello packets, measured across channels. Limits, modes and",
            "ipv6-prefix-length are LIVE after /sourbycraft reload. Windows are fixed 1 s buckets, so up to 2x a limit can pass",
            "across one boundary. Per-address: a peer's IPv4 address or IPv6 prefix; loopback is exempt. Behind BungeeCord,",
            "Velocity or the PROXY protocol (detected at startup, RESTART_REQUIRED) the TCP peer is the proxy: per-address socket",
            "limits are skipped and login starts are keyed by the forwarded player address. A per-address KICK reject does not",
            "consume the global budget."));
        docs.put("connection-guard.global", List.of("Across every peer. Defaults: 1000 sockets per second, 100 login starts per second."));
        docs.put("connection-guard.per-address", List.of("Per IPv4 address or IPv6 prefix. IPv6 peers are grouped by ipv6-prefix-length (1..128; the default /64 is one",
            "typical end-user allocation). Defaults: 20 sockets per second, 5 login starts per second."));
        return java.util.Collections.unmodifiableMap(docs);
    }

    /** Writes the header and the group comments into {@code yaml} (keys and values untouched). */
    private static void document(YamlConfiguration yaml) {
        yaml.options().setHeader(HEADER);
        for (var entry : GROUP_DOCS.entrySet()) {
            if (yaml.isConfigurationSection(entry.getKey())) yaml.setComments(entry.getKey(), entry.getValue());
        }
    }

    /**
     * The file a server without one gets at boot: every limit at its default, LOG everywhere, documented.
     * {@code build-data/sourbycraft-security.yml} is this text ({@code SecurityLimitPinTest}).
     */
    public static String seededDocument() {
        var yaml = new YamlConfiguration();
        for (Limit limit : Limit.values()) {
            yaml.set(limit.group + ".mode", Mode.LOG.name());
            yaml.set(limit.key, limit.fallback);
        }
        yaml.set(IPV6_PREFIX_KEY, 64);
        document(yaml);
        return yaml.saveToString();
    }

    /**
     * Called by the native TCP listener start with Spigot {@code settings.bungeecord}, Paper
     * {@code proxies.velocity.enabled} and {@code proxies.proxy-protocol}. Logs the resulting
     * admission keying once per change (a reload of this file does not repeat it).
     */
    public synchronized void configureProxyMode(boolean bungee, boolean velocity, boolean proxyProtocol) {
        var policy = new ProxyPolicy(bungee, velocity, proxyProtocol);
        var previous = settings;
        settings = new Settings(previous.rules(), previous.ipv6PrefixLength(), policy);
        if (policy.equals(previous.proxy()) || !policy.transport()) return;
        logger.accept("security admission: proxy forwarding configured (bungeecord=" + bungee + ", velocity=" + velocity
            + ", proxy-protocol=" + proxyProtocol + "). The TCP peer is the proxy, so connection-guard.per-address"
            + ".max-connections-per-second is not applied; max-login-starts-per-second keys the forwarded player address "
            + (policy.lateLogin() ? "after Velocity forwarding" : "at login Hello")
            + ". Loopback is always exempt from per-address limits; global limits still apply. Firewall the backend so only the proxy can connect.");
    }

    public static Scope begin(Channel channel) {
        var state = channel.attr(STATE).get();
        if (state == null) {
            var fresh = new ConnectionState();
            var previous = channel.attr(STATE).setIfAbsent(fresh);
            state = previous == null ? fresh : previous;
        }
        return INSTANCE.open(state);
    }

    /** Owned by the channel event loop; discarded with the channel. */
    public static final class ConnectionState {
        private final long[] counts = new long[Limit.values().length];
        private final long[] windows = new long[Limit.values().length];
        public ConnectionState() { Arrays.fill(windows, Long.MIN_VALUE); }
    }

    public final class Scope implements AutoCloseable {
        private final ConnectionState connection;
        private final Scope previous;
        private final Settings snapshot;
        private java.util.function.Supplier<String> logContext;
        private long observed;
        private Scope(ConnectionState connection) {
            this.connection = connection;
            this.previous = active.get();
            this.snapshot = settings;
        }
        @Override public void close() { if (previous == null) active.remove(); else active.set(previous); }
    }

    Scope open(ConnectionState state) {
        var scope = new Scope(state);
        active.set(scope);
        return scope;
    }

    boolean inPacket() { return active.get() != null; }
    public long violations(Limit limit) { return counters.get(limit.ordinal()); }

    /** Each limit counts at most one violation per packet; global per-limit log budget. */
    public void check(Limit limit, long measured) {
        var scope = active.get();
        if (scope == null) return; // NBT used by storage/plugins is outside the network policy
        Rule rule = scope.snapshot.rules().get(limit);
        if (rule.mode == Mode.OFF || measured <= rule.maximum) return;
        long mask = 1L << limit.ordinal();
        if ((scope.observed & mask) == 0) {
            scope.observed |= mask;
            long count = counters.incrementAndGet(limit.ordinal());
            long now = clock.getAsLong();
            long next = nextLog.get(limit.ordinal());
            if ((next == Long.MIN_VALUE || now - next >= 0) && nextLog.compareAndSet(limit.ordinal(), next, now + LOG_INTERVAL)) {
                logger.accept("security " + rule.mode + " " + limit.key + " measured=" + measured
                    + " limit=" + rule.maximum + " violations=" + count
                    + (scope.logContext == null ? "" : scope.logContext.get()));
            }
        }
        if (rule.mode == Mode.KICK) throw new Rejected(limit);
    }

    private void rate(Limit limit, long increment, long windowNanos) {
        var scope = active.get();
        if (scope == null) return;
        long window = Math.floorDiv(clock.getAsLong(), windowNanos);
        int ordinal = limit.ordinal();
        var connection = scope.connection;
        if (connection.windows[ordinal] != window) {
            connection.windows[ordinal] = window;
            connection.counts[ordinal] = 0;
        }
        long current = connection.counts[ordinal];
        connection.counts[ordinal] = current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
        check(limit, connection.counts[ordinal]);
    }

    /**
     * TCP accept observation before protocol setup, keyed by the transport peer. Behind a
     * configured proxy that peer is the proxy, so only the global limit applies. False only for
     * an explicit KICK rule.
     */
    public boolean connectionOpened(Channel channel) {
        if (!(channel instanceof io.netty.channel.socket.SocketChannel socket)) return true;
        return admission(socket.remoteAddress(), false, settings.proxy().transport(), true);
    }

    /**
     * Initial valid-state Login Hello, before profile verification/authentication work.
     * {@code remote} is {@code Connection.getRemoteAddress()}: already the forwarded player
     * address for PROXY protocol and BungeeCord. With Velocity modern forwarding the player
     * address arrives later, so Hello counts globally only and {@link #loginForwarded} applies
     * the per-address limit.
     */
    public boolean loginStarted(Channel channel, java.net.SocketAddress remote) {
        if (!(channel instanceof io.netty.channel.socket.SocketChannel)) return true;
        return admission(remote, true, settings.proxy().lateLogin(), true);
    }

    /** Velocity modern forwarding: the per-address login limit for the forwarded address only. */
    public boolean loginForwarded(Channel channel, java.net.SocketAddress remote) {
        if (!(channel instanceof io.netty.channel.socket.SocketChannel) || !settings.proxy().lateLogin()) return true;
        return admission(remote, true, false, false);
    }

    private boolean admission(java.net.SocketAddress socketAddress, boolean login, boolean skipAddress, boolean countGlobal) {
        // Numeric peer bytes only: no string parsing or name resolution. Local/Unix-domain
        // channels and unresolved addresses have no IP admission identity (outside this policy).
        if (!(socketAddress instanceof java.net.InetSocketAddress remote) || remote.getAddress() == null) return true;
        try (var scope = open(new ConnectionState())) {
            byte[] address = remote.getAddress().getAddress();
            int prefix = scope.snapshot.ipv6PrefixLength();
            scope.logContext = () -> " peer=" + AdmissionRateLimiter.addressLabel(address, prefix);
            Limit perAddress = login ? Limit.ADDRESS_LOGINS : Limit.ADDRESS_CONNECTIONS;
            Rule addressRule = scope.snapshot.rules().get(perAddress);
            // Loopback is exempt from per-address limits, like Paper's connection throttle.
            skipAddress |= remote.getAddress().isLoopbackAddress();
            var counts = admissions.record(address, login, clock.getAsLong(), prefix, skipAddress, countGlobal,
                addressRule.maximum(), addressRule.mode() == Mode.KICK);
            if (counts.address() >= 0) check(perAddress, counts.address());
            if (counts.global() >= 0) check(login ? Limit.ADMISSION_LOGINS : Limit.ADMISSION_CONNECTIONS, counts.global());
            return true;
        } catch (Rejected rejected) {
            return false;
        }
    }

    public void packet(int bytes) {
        rate(Limit.CONNECTION_RATE, 1, 1_000_000_000L);
        rate(Limit.CONNECTION_BYTES, bytes, 1_000_000_000L);
    }
    /** Historical max-per-tick means a monotonic 50 ms bucket, independent of region tick rates. */
    public void operation(Limit limit) { rate(limit, 1, 50_000_000L); }
    public void book(java.util.List<String> pages, java.util.Optional<String> title) {
        long chars = title.map(String::length).orElse(0);
        for (String page : pages) chars += page.length();
        check(Limit.BOOK_CHARS, chars);
    }
    public void sign(String[] lines) {
        long total = 0;
        for (String line : lines) { check(Limit.SIGN_LINE, line.length()); total += line.length(); }
        check(Limit.SIGN_TOTAL, total);
    }
}
