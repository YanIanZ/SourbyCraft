package dev.iyanz.sourbycraft.testplugin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * {@code /stallprobe <global|region> <sleep|lock|spin> <seconds>}: blocks a tick on purpose so the
 * stall recorder ({@code logs/stalls/}) can be seen working on a test server. Registered only with
 * {@code -Dsourby.testplugin.probes=true}; CI boots without it. Never run it on a server with players.
 *
 * <ul>
 *   <li>{@code global}: the task runs on the global region, so the global tick stops for the duration;
 *       {@code region}: it runs on the region at the first world's spawn (that region stops; the global tick
 *       keeps going).</li>
 *   <li>{@code sleep}: {@code Thread.sleep} in the tick (the thread shows TIMED_WAITING inside tick code).</li>
 *   <li>{@code lock}: a helper thread {@code stallprobe-lock-owner} holds a monitor and the tick blocks
 *       entering it (BLOCKED, with the owner named).</li>
 *   <li>{@code spin}: a busy loop (RUNNABLE, per-thread CPU time grows between dumps).</li>
 * </ul>
 */
final class StallProbe implements BasicCommand {

    private final Plugin plugin;

    StallProbe(final Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final CommandSourceStack source, final String[] args) {
        if (args.length != 3) {
            this.log("usage: /stallprobe <global|region> <sleep|lock|spin> <seconds>");
            return;
        }
        final String where = args[0].toLowerCase(Locale.ROOT);
        final String how = args[1].toLowerCase(Locale.ROOT);
        final int seconds;
        try {
            seconds = Integer.parseInt(args[2]);
        } catch (final NumberFormatException invalid) {
            this.log("seconds must be a whole number");
            return;
        }
        if (seconds < 1 || seconds > 600 || !(where.equals("global") || where.equals("region"))
            || !(how.equals("sleep") || how.equals("lock") || how.equals("spin"))) {
            this.log("usage: /stallprobe <global|region> <sleep|lock|spin> <1-600>");
            return;
        }
        final Runnable block = () -> {
            this.log("start where=" + where + " how=" + how + " seconds=" + seconds
                + " thread=" + Thread.currentThread().getName());
            this.block(how, seconds);
            this.log("end where=" + where + " how=" + how);
        };
        if (where.equals("global")) {
            Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> block.run());
        } else {
            final Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
            Bukkit.getRegionScheduler().run(this.plugin, spawn, task -> block.run());
        }
    }

    private void block(final String how, final int seconds) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        switch (how) {
            case "sleep" -> {
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(100L);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            case "spin" -> {
                long x = 0;
                while (System.nanoTime() < deadline) {
                    x += System.nanoTime() & 1L;
                }
                if (x < 0) {
                    this.log("unreachable " + x);
                }
            }
            default -> this.blockOnLock(seconds);
        }
    }

    private void blockOnLock(final int seconds) {
        final Object lock = new Object();
        final CountDownLatch held = new CountDownLatch(1);
        final Thread owner = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "stallprobe-lock-owner");
        owner.setDaemon(true);
        owner.start();
        try {
            held.await();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (lock) {
            // acquired when the owner releases it
        }
    }

    private void log(final String message) {
        this.plugin.getLogger().info("STALLPROBE " + message);
    }
}
