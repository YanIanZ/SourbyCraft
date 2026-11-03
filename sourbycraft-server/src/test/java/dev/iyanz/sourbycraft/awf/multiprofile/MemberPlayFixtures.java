package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileAdapterDescriptor;
import dev.iyanz.sourbycraft.api.profile.ProfileDataCriticality;
import dev.iyanz.sourbycraft.api.profile.ProfileDataScope;
import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Shared fixtures of the Build 53 member-play cases (COOP-DESIGN §6 and §10 slice 5, RFC E03): settings with the
 * three Build 53 keys, a stateful player port in place of a server, and reflective {@link Player} / {@link Plugin}
 * stand-ins for the adapter registry (its capture and apply only hand the player object to the adapter, which here
 * ignores it).
 *
 * <p>Deterministic: no sleeps. A capture or an apply that a case wants to hold is a future the case completes itself;
 * "the entity thread entered" is a latch the case awaits with a bounded wait.</p>
 */
final class MemberPlayFixtures {

    private MemberPlayFixtures() {}

    // ------------------------------------------------------------------ settings

    /**
     * MultiProfile and co-op on (invites 5 min, votes 10 min, 35 days), switching enabled with {@code timeoutMillis}, and
     * the Build 53 keys as given.
     */
    static ProfileSettings playSettings(final boolean authoritative, final int timeoutMillis, final Set<String> required) {
        final ProfileSettings base = CoopMembershipFixtures.coopSettings();
        return new ProfileSettings(base.enabled(), base.mode(), base.defaultSlots(), base.maxSlots(),
            new ProfileSettings.Switching(true, timeoutMillis, true), base.isolation(), base.worlds(), base.coordination(),
            base.directory(), base.repository(), base.types(), base.names(), base.migration(), base.coop(),
            ProfileSettings.DEFAULT_SHUTDOWN, new ProfileSettings.Login(authoritative), new ProfileSettings.Adapters(required));
    }

    /** The Build 53 defaults: authoritative login on, 15 s switch timeout, nothing forced REQUIRED. */
    static ProfileSettings playSettings() {
        return playSettings(true, 15_000, Set.of());
    }

    // ------------------------------------------------------------------ states

    /** A state told apart by its level (a {@link ProfileState} with items compares its item bytes by identity). */
    static ProfileState level(final int level) {
        final ProfileState empty = ProfileState.empty();
        return new ProfileState(ProfileState.SCHEMA, List.of(), List.of(), List.of(), List.of(), level, 0.0f, empty.health(),
            empty.food(), empty.saturation(), List.of(), null, null, Map.of());
    }

    static ProfileState level(final int level, final Map<String, byte[]> adapters) {
        return level(level).withAdapters(adapters);
    }

    static byte[] bytes(final int length, final int fill) {
        final byte[] data = new byte[length];
        java.util.Arrays.fill(data, (byte) fill);
        return data;
    }

    // ------------------------------------------------------------------ Bukkit stand-ins

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    /** A {@link Player} that answers only its id and its name; every other call returns a default. */
    static Player player(final UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUniqueId":
                    return id;
                case "getName":
                    return "player-" + id;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Player(" + id + ")";
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    /** An enabled {@link Plugin} named {@code name}; every other call returns a default. */
    static Plugin plugin(final String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getName":
                    return name;
                case "isEnabled":
                    return true;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Plugin(" + name + ")";
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    /** What a {@link TestAdapter} does on capture: return these bytes ({@code null} = nothing), or throw. */
    interface CaptureBehaviour {
        byte[] capture() throws Exception;
    }

    /**
     * A {@link ProfileStateAdapter} whose capture and apply are scripted; it records what it was asked to apply.
     * {@code descriptor} {@code null} keeps the interface default (BEST_EFFORT, profile scoped).
     */
    static class TestAdapter implements ProfileStateAdapter {

        final String id;
        final int version;
        final Plugin plugin;
        final ProfileAdapterDescriptor descriptor;
        final AtomicReference<CaptureBehaviour> onCapture = new AtomicReference<>(() -> null);
        final AtomicBoolean failApply = new AtomicBoolean();
        final List<byte[]> applied = new CopyOnWriteArrayList<>();
        /** What {@link #migrate} returns for older stored bytes ({@code null}: not supported, the interface default). */
        final AtomicReference<java.util.function.BiFunction<Integer, byte[], byte[]>> onMigrate = new AtomicReference<>();

        TestAdapter(final String id, final int version, final Plugin plugin, final ProfileAdapterDescriptor descriptor) {
            this.id = id;
            this.version = version;
            this.plugin = plugin;
            this.descriptor = descriptor;
        }

        static TestAdapter bestEffort(final String id, final Plugin plugin) {
            return new TestAdapter(id, 1, plugin, null);
        }

        static TestAdapter required(final String id, final Plugin plugin) {
            return new TestAdapter(id, 1, plugin, new ProfileAdapterDescriptor(id, 1, ProfileDataScope.PROFILE_SCOPED,
                ProfileDataCriticality.REQUIRED, ProfileAdapterDescriptor.MAX_BYTES_LIMIT));
        }

        TestAdapter returning(final byte[] data) {
            this.onCapture.set(() -> data);
            return this;
        }

        TestAdapter throwing() {
            this.onCapture.set(() -> {
                throw new IllegalStateException("scripted capture failure of " + this.id);
            });
            return this;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public int version() {
            return this.version;
        }

        @Override
        public byte[] capture(final Player player) throws Exception {
            return this.onCapture.get().capture();
        }

        @Override
        public void apply(final Player player, final byte[] data) {
            if (this.failApply.get()) throw new IllegalStateException("scripted apply failure of " + this.id);
            this.applied.add(data.clone());
        }

        @Override
        public Plugin plugin() {
            return this.plugin;
        }

        @Override
        public byte[] migrate(final int fromVersion, final byte[] data) {
            final java.util.function.BiFunction<Integer, byte[], byte[]> migration = this.onMigrate.get();
            return migration == null ? null : migration.apply(fromVersion, data);
        }

        @Override
        public ProfileAdapterDescriptor descriptor() {
            return this.descriptor == null ? ProfileStateAdapter.super.descriptor() : this.descriptor;
        }
    }

    // ------------------------------------------------------------------ the player port

    /**
     * A player port with a live state per player (what is "on" the player), in place of a server. With a
     * {@link ProfileStateAdapters registry} the capture asks the registered adapters exactly as
     * {@code BukkitPlayerStatePort} does (a required adapter that fails fails the capture), and the merge and the
     * apply validation are the registry's; without one the {@link PlayerStatePort} defaults apply.
     */
    static class PlayingPorts extends CoopFixtures.QuietPlayers {

        final Map<UUID, ProfileState> live = new ConcurrentHashMap<>();
        final ProfileStateAdapters registry;
        /** Every state handed to an apply that reached the player, in order. */
        final List<ProfileState> applied = new CopyOnWriteArrayList<>();
        /** When set, the next capture returns this future instead of the live state (the case completes it). */
        final AtomicReference<CompletableFuture<PlayerStatePort.CaptureResult>> heldCapture = new AtomicReference<>();
        /** Counted down when a capture starts. */
        final AtomicReference<CountDownLatch> captureEntered = new AtomicReference<>();
        /** When set, the next cancellable apply waits for this future and then checks its cancellation. */
        final AtomicReference<CompletableFuture<Void>> heldApply = new AtomicReference<>();
        /** Counted down when a held apply was handed over. */
        final AtomicReference<CountDownLatch> applyEntered = new AtomicReference<>();
        /** Held applies that found themselves cancelled when they finally ran, and did nothing. */
        final AtomicInteger cancelledApplies = new AtomicInteger();
        /** When set, the next apply fails with it (the player state is not changed). */
        final AtomicReference<RuntimeException> failNextApply = new AtomicReference<>();
        /** When true together with {@link #failNextApply}, the player leaves while that apply fails. */
        final AtomicBoolean offlineOnFailedApply = new AtomicBoolean();
        /** The modification time of this node's vanilla playerdata file per player; absent = no file (the default). */
        final Map<UUID, Long> vanillaModified = new ConcurrentHashMap<>();

        PlayingPorts() {
            this(null);
        }

        PlayingPorts(final ProfileStateAdapters registry) {
            this.registry = registry;
        }

        ProfileState state(final UUID player) {
            return this.live.getOrDefault(player, ProfileState.empty());
        }

        int levelOf(final UUID player) {
            return state(player).level();
        }

        @Override
        public CompletableFuture<ProfileState> capture(final UUID playerId, final ProfileIsolation isolation) {
            return captureDetailed(playerId, isolation).thenApply(PlayerStatePort.CaptureResult::state);
        }

        @Override
        public CompletableFuture<PlayerStatePort.CaptureResult> captureDetailed(final UUID playerId, final ProfileIsolation isolation) {
            final CountDownLatch entered = this.captureEntered.getAndSet(null);
            if (entered != null) entered.countDown();
            final CompletableFuture<PlayerStatePort.CaptureResult> held = this.heldCapture.getAndSet(null);
            if (held != null) return held;
            final ProfileState state = state(playerId);
            if (this.registry == null) return CompletableFuture.completedFuture(PlayerStatePort.CaptureResult.of(state));
            try {
                final ProfileStateAdapters.Capture adapters = this.registry.capture(player(playerId));
                return CompletableFuture.completedFuture(new PlayerStatePort.CaptureResult(state.withAdapters(adapters.data()), adapters));
            } catch (final RuntimeException failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }

        @Override
        public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state, final ProfileIsolation isolation) {
            final RuntimeException failure = this.failNextApply.getAndSet(null);
            if (failure != null) {
                if (this.offlineOnFailedApply.getAndSet(false)) this.online.remove(playerId);
                return CompletableFuture.failedFuture(failure);
            }
            this.live.put(playerId, state);
            this.applied.add(state);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state, final ProfileIsolation isolation,
                                             final BooleanSupplier cancelled) {
            final CompletableFuture<Void> held = this.heldApply.getAndSet(null);
            if (held == null) return apply(playerId, state, isolation);
            final CountDownLatch entered = this.applyEntered.getAndSet(null);
            if (entered != null) entered.countDown();
            // The "entity thread" runs the apply when the case releases it, and checks the cancellation then.
            return held.thenRun(() -> {
                if (cancelled.getAsBoolean()) {
                    this.cancelledApplies.incrementAndGet();
                    return;
                }
                this.live.put(playerId, state);
                this.applied.add(state);
            });
        }

        @Override
        public java.util.OptionalLong vanillaPlayerdataModifiedMillis(final UUID playerId) {
            final Long modified = this.vanillaModified.get(playerId);
            return modified == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(modified);
        }

        @Override
        public void validateApply(final ProfileState state) {
            if (this.registry != null) this.registry.validateApply(state.adapters());
        }

        @Override
        public ProfileState carryAdapters(final ProfileState captured, final ProfileState previous) {
            if (this.registry == null) return super.carryAdapters(captured, previous);
            return captured.withAdapters(this.registry.carryForward(captured.adapters(), previous.adapters()));
        }

        @Override
        public ProfileState carryAdapters(final ProfileState captured, final ProfileState previous,
                                          final ProfileStateAdapters.Capture capture) {
            if (this.registry == null) return super.carryAdapters(captured, previous, capture);
            return captured.withAdapters(this.registry.carryForward(captured.adapters(), previous.adapters(), capture));
        }
    }
}
