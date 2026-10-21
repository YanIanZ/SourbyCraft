package dev.iyanz.sourbycraft.testplugin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ServerTickManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;

/**
 * Logs {@code LEGACYPROBE} lines for the deprecated synchronous {@code PlayerPreLoginEvent} and
 * {@code PlayerChatEvent} and drives {@code ServerTickManager} on {@code /tickprobe}, so a short
 * test-server boot with a headless client shows each event firing, on which thread, and that a
 * denial, a cancel and a format change are honoured. Registered only with
 * {@code -Dsourby.testplugin.probes=true}.
 *
 * <p>Pre-login: a name starting with {@code LegacyDeny} is denied with {@link #DENY_MESSAGE}; a name
 * starting with {@code LegacyNullDeny} is denied without a kick message (must still be refused).
 * {@code /chatprobe <player> <global|async|entity> <message>} calls {@code Player#chat} from that
 * scheduler and logs whether it returned or threw.
 * Chat: a message containing {@code cancel-me} is cancelled; any other gets the format
 * {@code [legacy] <name>: <message>}. The async chat event logs what it sees afterwards.</p>
 */
@SuppressWarnings("deprecation")
final class LegacyEventsProbe implements Listener, BasicCommand {

    static final String DENY_MESSAGE = "LEGACYPROBE denied by the legacy PlayerPreLoginEvent";

    private final Plugin plugin;

    LegacyEventsProbe(final Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(final PlayerPreLoginEvent event) {
        final boolean deny = event.getName().startsWith("LegacyDeny");
        if (deny) {
            event.disallow(PlayerPreLoginEvent.Result.KICK_OTHER, Component.text(DENY_MESSAGE));
        } else if (event.getName().startsWith("LegacyNullDeny")) {
            event.disallow(PlayerPreLoginEvent.Result.KICK_OTHER, (Component) null);
        }
        Bukkit.getLogger().info("LEGACYPROBE prelogin name=" + event.getName() + " thread=" + Thread.currentThread().getName()
            + " globalTick=" + Bukkit.isGlobalTickThread() + " result=" + event.getResult());
    }

    @EventHandler
    public void onLegacyChat(final PlayerChatEvent event) {
        final boolean cancel = event.getMessage().contains("cancel-me");
        if (cancel) {
            event.setCancelled(true);
        } else {
            event.setFormat("[legacy] %1$s: %2$s");
        }
        Bukkit.getLogger().info("LEGACYPROBE chat player=" + event.getPlayer().getName() + " thread=" + Thread.currentThread().getName()
            + " ownsPlayer=" + Bukkit.isOwnedByCurrentRegion(event.getPlayer()) + " cancelled=" + event.isCancelled()
            + " message=" + event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterLegacyChat(final AsyncChatEvent event) {
        Bukkit.getLogger().info("LEGACYPROBE asyncchat player=" + event.getPlayer().getName() + " thread=" + Thread.currentThread().getName()
            + " cancelled=" + event.isCancelled() + " message=" + PlainTextComponentSerializer.plainText().serialize(event.message()));
    }

    /** {@code /tickprobe}: freeze, step, unfreeze, sprint and rate through the Bukkit API, one global tick apart. */
    @Override
    public void execute(final CommandSourceStack source, final String[] args) {
        final ServerTickManager ticks = Bukkit.getServerTickManager();
        if (args.length == 2 && "region".equals(args[0])) {
            this.regionPause(ticks, args[1]);
            return;
        }
        log("start", ticks);
        ticks.setFrozen(true);
        log("after-setFrozen(true)", ticks);
        later(2, () -> {
            log("frozen", ticks);
            log("step(5)=" + ticks.stepGameIfFrozen(5), ticks);
            later(2, () -> {
                log("stepping", ticks);
                later(10, () -> {
                    log("step-done", ticks);
                    ticks.setFrozen(false);
                    later(2, () -> {
                        log("unfrozen", ticks);
                        log("sprint(40)=" + ticks.requestGameToSprint(40), ticks);
                        later(5, () -> {
                            log("sprinting", ticks);
                            log("stopSprinting=" + ticks.stopSprinting(), ticks);
                            final float before = ticks.getTickRate();
                            ticks.setTickRate(25.0F);
                            log("rate25", ticks);
                            ticks.setTickRate(before);
                            try {
                                ticks.setTickRate(0.5F);
                                log("RANGE_NOT_CHECKED", ticks);
                            } catch (final IllegalArgumentException expected) {
                                log("range-checked", ticks);
                            }
                            later(2, () -> log("done", ticks));
                        });
                    });
                });
            });
        });
    }

    /**
     * {@code /tickprobe region <player>}: pauses the player's region through the region API, then
     * unfreezes the server and changes the tick rate; the player's client must keep receiving
     * frozen=true (its region's combined state) until the region plays again.
     */
    private void regionPause(final ServerTickManager ticks, final String name) {
        final org.bukkit.entity.Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            Bukkit.getLogger().info("LEGACYPROBE region no-player " + name);
            return;
        }
        player.getScheduler().run(this.plugin, task -> this.regionPauseOnOwner(ticks, player.getLocation().getChunk()), null);
    }

    private void regionPauseOnOwner(final ServerTickManager ticks, final org.bukkit.Chunk chunk) {
        final dev.iyanz.sourbycraft.api.region.RegionThreadingTickManager regions = Bukkit.getServer().getRegionThreadingTickRateManager();
        final float before = ticks.getTickRate();
        regions.getHandleAt(chunk).pause();
        log("region-paused", ticks);
        later(5, () -> {
            ticks.setFrozen(false);
            log("region-paused-setFrozen(false)", ticks);
            later(5, () -> {
                ticks.setTickRate(30.0F);
                log("region-paused-rate30", ticks);
                later(5, () -> {
                    ticks.setTickRate(before);
                    regions.getHandleAt(chunk).play();
                    log("region-played", ticks);
                    later(5, () -> log("region-done", ticks));
                });
            });
        });
    }

    /** {@code /chatprobe <player> <global|async|entity> <message...>}. */
    BasicCommand chatProbe() {
        return (source, args) -> {
            if (args.length < 3) {
                Bukkit.getLogger().info("LEGACYPROBE chatprobe usage: <player> <global|async|entity> <message>");
                return;
            }
            final org.bukkit.entity.Player player = Bukkit.getPlayerExact(args[0]);
            if (player == null) {
                Bukkit.getLogger().info("LEGACYPROBE chatprobe no-player " + args[0]);
                return;
            }
            final String mode = args[1];
            final String message = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            final Runnable chat = () -> {
                try {
                    player.chat(message);
                    Bukkit.getLogger().info("LEGACYPROBE chatprobe mode=" + mode + " returned thread=" + Thread.currentThread().getName()
                        + " ownsPlayer=" + Bukkit.isOwnedByCurrentRegion(player));
                } catch (final RuntimeException e) {
                    Bukkit.getLogger().info("LEGACYPROBE chatprobe mode=" + mode + " threw=" + e + " thread=" + Thread.currentThread().getName());
                }
            };
            switch (mode) {
                case "global" -> Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> chat.run());
                case "async" -> Bukkit.getAsyncScheduler().runNow(this.plugin, task -> chat.run());
                default -> player.getScheduler().run(this.plugin, task -> chat.run(), null);
            }
        };
    }

    private void later(final long ticks, final Runnable action) {
        Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, task -> action.run(), ticks);
    }

    private static void log(final String step, final ServerTickManager ticks) {
        Bukkit.getLogger().info("LEGACYPROBE tick " + step + " frozen=" + ticks.isFrozen() + " runningNormally=" + ticks.isRunningNormally()
            + " stepping=" + ticks.isStepping() + " ticksToRun=" + ticks.getFrozenTicksToRun() + " sprinting=" + ticks.isSprinting()
            + " rate=" + ticks.getTickRate() + " thread=" + Thread.currentThread().getName());
    }
}
