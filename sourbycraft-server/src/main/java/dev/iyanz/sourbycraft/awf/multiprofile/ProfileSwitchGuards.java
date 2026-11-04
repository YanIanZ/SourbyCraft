package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchGuard;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Switch policy (Build 55, COOP-DESIGN slice 7): the registered {@link ProfileSwitchGuard}s and the per-player
 * switch cooldown.
 *
 * <p><b>Cooldown.</b> {@code switching.cooldown-seconds} (default 0 = off; Hypixel parity is 90). Only a
 * <em>committed</em> switch starts it ({@link #committed}); a refused, vetoed, failed or rolled-back switch does not.
 * The timestamps live in memory on this node: a restart clears them (documented), a second node knows nothing of
 * them (OD-1: a profile is played on one node). The check runs at admission, in the switch's first I/O step.</p>
 *
 * <p><b>Guards.</b> Evaluated once per switch by {@link #evaluate}, after admission and the target world's
 * readiness and before the player is frozen or captured; off every game thread, one guard after the other, with one
 * budget of {@value #GUARD_BUDGET_MILLIS} ms for all. A guard that returns a reason, throws or runs out of time
 * vetoes with a WARN naming the plugin. The evaluation is bound to the switch's reservation: the engine re-checks
 * the player's reservation, online state, deadline and the target's state after the hooks return, and a guard that
 * calls {@code switchProfile} for the same player is refused {@code SWITCH_IN_PROGRESS} by the running switch's
 * reservation (no reentrancy into the same switch).</p>
 *
 * <p><b>Its own lane (review P2-5).</b> Guards run on this class's own small bounded pool ({@value #GUARD_THREADS}
 * daemon threads {@code SourbyCraft-MP-switch-guard-N}, {@value #GUARD_QUEUE} waiting evaluations), never on the
 * shared {@code VirtualExecutor} whose 64 permits AWF world operations use: a plugin guard that hangs can hold at most
 * these threads, and when the budget runs out the evaluation is <em>cancelled with interrupt</em> so the thread is
 * freed (a guard that ignores the interrupt still blocks only its own lane thread). An evaluation the lane cannot take
 * (both threads busy and the queue full) is refused at once as a veto, never queued elsewhere. {@link #close} stops
 * the lane.</p>
 */
public final class ProfileSwitchGuards {

    /** One time budget for all guards of one switch. */
    public static final int GUARD_BUDGET_MILLIS = 2000;
    /** Threads of the guard lane. */
    static final int GUARD_THREADS = 2;
    /** Evaluations waiting for a guard-lane thread; beyond it an evaluation is refused as a veto. */
    static final int GUARD_QUEUE = 64;
    /** Longest veto reason kept. */
    static final int MAX_REASON = 128;

    /** A registered guard with the plugin that owns it. */
    record Registered(String plugin, ProfileSwitchGuard guard) {}

    /** The outcome of evaluating the guards: {@code null} reason means allowed. */
    public record Verdict(String reason, String plugin, boolean timedOut) {
        public static final Verdict ALLOWED = new Verdict(null, null, false);

        public boolean allowed() {
            return this.reason == null;
        }
    }

    private final long cooldownMillis;
    private final LongSupplier clock;
    private final long budgetMillis;
    private final ThreadPoolExecutor lane;
    private final List<Registered> guards = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, Long> lastCommitted = new ConcurrentHashMap<>();
    /** Most players remembered; beyond it the oldest timestamps are dropped (a dropped one only shortens a cooldown). */
    static final int MAX_REMEMBERED = 16_384;

    public ProfileSwitchGuards(final int cooldownSeconds) {
        this(cooldownSeconds, System::currentTimeMillis);
    }

    ProfileSwitchGuards(final int cooldownSeconds, final LongSupplier clock) {
        this(cooldownSeconds, clock, GUARD_BUDGET_MILLIS);
    }

    /** With an explicit guard budget (tests shorten it). */
    ProfileSwitchGuards(final int cooldownSeconds, final LongSupplier clock, final long budgetMillis) {
        if (cooldownSeconds < 0) throw new IllegalArgumentException("cooldownSeconds " + cooldownSeconds);
        if (budgetMillis < 1L) throw new IllegalArgumentException("budgetMillis " + budgetMillis);
        this.cooldownMillis = cooldownSeconds * 1000L;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetMillis = budgetMillis;
        final AtomicInteger next = new AtomicInteger(1);
        // Bounded on both axes (UnboundedQueueAuditTest): GUARD_THREADS threads, GUARD_QUEUE waiting evaluations.
        this.lane = new ThreadPoolExecutor(GUARD_THREADS, GUARD_THREADS, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(GUARD_QUEUE), task -> {
            final Thread thread = new Thread(task, "SourbyCraft-MP-switch-guard-" + next.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.lane.allowCoreThreadTimeOut(true);
    }

    /** Stops the guard lane: running evaluations are interrupted, waiting ones dropped. The engine calls it on close. */
    public void close() {
        this.lane.shutdownNow();
    }

    /** Evaluations in flight or waiting on the guard lane (diagnostics). */
    int laneLoad() {
        return this.lane.getActiveCount() + this.lane.getQueue().size();
    }

    public long cooldownMillis() {
        return this.cooldownMillis;
    }

    // ------------------------------------------------------------------ cooldown

    /** Milliseconds the player must still wait, {@code 0} when they may switch now. */
    public long cooldownRemainingMillis(final UUID playerId) {
        if (this.cooldownMillis == 0L) return 0L;
        final Long last = this.lastCommitted.get(playerId);
        if (last == null) return 0L;
        return Math.max(0L, last + this.cooldownMillis - this.clock.getAsLong());
    }

    /** A switch of {@code playerId} committed now: the cooldown starts. */
    public void committed(final UUID playerId) {
        if (this.cooldownMillis == 0L) return;
        if (this.lastCommitted.size() >= MAX_REMEMBERED) {
            final long now = this.clock.getAsLong();
            this.lastCommitted.values().removeIf(at -> at + this.cooldownMillis <= now);
            if (this.lastCommitted.size() >= MAX_REMEMBERED) {
                UUID oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (final var entry : this.lastCommitted.entrySet()) {
                    if (entry.getValue() < oldestAt) {
                        oldestAt = entry.getValue();
                        oldest = entry.getKey();
                    }
                }
                if (oldest != null) this.lastCommitted.remove(oldest);
            }
        }
        this.lastCommitted.put(playerId, this.clock.getAsLong());
    }

    /** Forgets a player's cooldown (an operator's reset); {@code true} when one was remembered. */
    public boolean clearCooldown(final UUID playerId) {
        return this.lastCommitted.remove(playerId) != null;
    }

    // ------------------------------------------------------------------ guards

    /**
     * @throws IllegalStateException at {@link AuroraProfiles#MAX_SWITCH_GUARDS} guards
     */
    public void register(final String plugin, final ProfileSwitchGuard guard) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(guard, "guard");
        synchronized (this.guards) {
            for (final Registered existing : this.guards) {
                if (existing.guard() == guard) return;
            }
            if (this.guards.size() >= AuroraProfiles.MAX_SWITCH_GUARDS) {
                throw new IllegalStateException("at most " + AuroraProfiles.MAX_SWITCH_GUARDS + " switch guards are registered; "
                    + plugin + "'s was refused");
            }
            this.guards.add(new Registered(plugin, guard));
        }
    }

    /** Removes {@code guard} when {@code plugin} registered it; {@code true} when something was removed. */
    public boolean unregister(final String plugin, final ProfileSwitchGuard guard) {
        return this.guards.removeIf(registered -> registered.guard() == guard && registered.plugin().equals(plugin));
    }

    /** Removes every guard of {@code plugin}; the count removed. */
    public int unregisterAll(final String plugin) {
        int removed = 0;
        for (final Registered registered : new ArrayList<>(this.guards)) {
            if (registered.plugin().equals(plugin) && this.guards.remove(registered)) removed++;
        }
        return removed;
    }

    public int guardCount() {
        return this.guards.size();
    }

    /** The plugins with a registered guard, for diagnostics. */
    public List<String> guardOwners() {
        final List<String> owners = new ArrayList<>();
        for (final Registered registered : this.guards) owners.add(registered.plugin());
        return owners;
    }

    /**
     * Evaluates every guard off the calling thread, on the guard lane, within the budget ({@link #GUARD_BUDGET_MILLIS}
     * by default). Completes (never fails) with the verdict: the first refusal wins; an exception, the budget running
     * out or a full lane is a refusal. On a timeout the running evaluation is cancelled with interrupt, so a hung guard
     * does not keep the lane thread (review P2-5). With no guard registered it completes at once with
     * {@link Verdict#ALLOWED} and uses no thread.
     */
    public CompletableFuture<Verdict> evaluate(final UUID playerId, final UUID fromProfileId, final UUID toProfileId) {
        final List<Registered> snapshot = new ArrayList<>(this.guards);
        if (snapshot.isEmpty()) return CompletableFuture.completedFuture(Verdict.ALLOWED);
        final CompletableFuture<Verdict> evaluated = new CompletableFuture<>();
        final Future<?> running;
        try {
            running = this.lane.submit(() -> {
                try {
                    evaluated.complete(evaluateNow(snapshot, playerId, fromProfileId, toProfileId));
                } catch (final Throwable failed) {
                    evaluated.completeExceptionally(failed);
                }
            });
        } catch (final RejectedExecutionException full) {
            SourbyLogger.warn("AWF MultiProfile: the switch guard lane is full or stopped (" + laneLoad() + " evaluations in flight);"
                + " the switch of " + playerId + " is refused");
            return CompletableFuture.completedFuture(new Verdict("the switch guards are busy; try again in a moment", null, false));
        }
        return evaluated.orTimeout(this.budgetMillis, TimeUnit.MILLISECONDS).handle((verdict, failed) -> {
            if (failed == null) return verdict;
            Throwable cause = failed;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof TimeoutException) {
                // Free the lane thread: the guard that is still running is interrupted and its late answer is ignored.
                running.cancel(true);
                SourbyLogger.warn("AWF MultiProfile: the switch guards (" + String.join(", ", guardOwners()) + ") did not answer within "
                    + this.budgetMillis + " ms; the switch of " + playerId + " is refused and the evaluation was interrupted");
                return new Verdict("the switch guards did not answer in time", null, true);
            }
            SourbyLogger.warn("AWF MultiProfile: the switch guards could not be evaluated (" + cause + "); the switch of " + playerId
                + " is refused");
            return new Verdict("the switch guards could not be evaluated", null, false);
        });
    }

    /** The guards one after the other on the lane thread; stops at the first refusal or when the thread was interrupted. */
    private static Verdict evaluateNow(final List<Registered> snapshot, final UUID playerId, final UUID fromProfileId, final UUID toProfileId) {
        for (final Registered registered : snapshot) {
            if (Thread.currentThread().isInterrupted()) return new Verdict("the switch guards did not answer in time", null, true);
            final String reason;
            try {
                reason = registered.guard().refusal(playerId, fromProfileId, toProfileId);
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("AWF MultiProfile: the switch guard of " + registered.plugin() + " threw " + failed
                    + "; the switch of " + playerId + " is refused");
                return new Verdict("a switch guard of " + registered.plugin() + " failed", registered.plugin(), false);
            }
            if (reason != null) {
                final String kept = reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
                return new Verdict(kept.isBlank() ? "refused by " + registered.plugin() : kept, registered.plugin(), false);
            }
        }
        return Verdict.ALLOWED;
    }
}
