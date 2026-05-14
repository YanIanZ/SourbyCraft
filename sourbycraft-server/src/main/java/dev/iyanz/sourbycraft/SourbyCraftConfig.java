    private static void startIdleTimeoutChecker() {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(
            org.bukkit.Bukkit.getPluginManager().getPlugins()[0],
            () -> {
                for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                    long idle = System.currentTimeMillis() - player.getLastActionTime();
                    if (idle > idleTimeout * 1000L) {
                        player.kick(net.kyori.adventure.text.Component.text("Idle timeout", SourbyCraftColors.DIM));
                    }
                }
            },
            1200L, 1200L // every 60 seconds
        );
    }