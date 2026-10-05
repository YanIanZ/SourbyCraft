package dev.iyanz.aurora.engine;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import dev.iyanz.aurora.engine.configuration.ConfigurationProvider;
import dev.iyanz.aurora.engine.configuration.Part;
import dev.iyanz.aurora.engine.configuration.Resolver;
import dev.iyanz.aurora.engine.configuration.Style;
import dev.iyanz.aurora.engine.configuration.Undocumented;
import dev.iyanz.aurora.engine.configuration.Validator;
import io.canvasmc.canvas.simd.SIMDDetection;
import dev.iyanz.aurora.engine.threadedregions.scheduler.AffinitySchedulerThreadPool;
import dev.iyanz.aurora.engine.util.FasterRandomSource;
import dev.iyanz.aurora.engine.util.LockedReference;
import dev.iyanz.aurora.engine.util.TimeSpan;
import dev.iyanz.aurora.engine.util.Util;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.random.RandomGeneratorFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jetbrains.annotations.UnknownNullability;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"FieldMayBeFinal", "unused"})
@NullMarked
public class GlobalConfiguration extends Part {

    private static final Path CONFIG_PATH = Path.of("config/canvas-server.yml").toAbsolutePath().normalize();
    private static final String BROADCAST_PERMISSION = "canvas.broadcasting.receiver";

    protected static final int CHAR_LIM = 90;

    public static final Logger LOGGER = LoggerFactory.getLogger("Aurora"); // SourbyCraft - engine config speaks as the engine: [CanvasMC] -> [Aurora]
    public static final LockedReference<TimeSpan> AUTOSAVE_SPAN = new LockedReference<>(null);

    public static final int INFO = 0;
    public static final int WARN = 1;
    public static final int ERROR = 2;

    @UnknownNullability("nonnull after reload is called")
    private static GlobalConfiguration INSTANCE;
    private static boolean ENABLE_FASTER_RANDOM = true;

    static {
        // if we surround this in try-catch and do any logging we
        // actually just drown any error in log4j errors too
        reload();
    }

    public static void init() {
        // no-op, just for static load from reload()
    }

    public static void reload() {
        LOGGER.info("Loading Aurora region configuration (config/canvas-server.yml)");
        ConfigurationProvider.buildSolidConfiguration(
            CONFIG_PATH,
            GlobalConfiguration::new,
            CHAR_LIM,
            new Resolver<>() {
                @Override
                public void onDiffAdd(final String fullyQualifiedName) {
                    LOGGER.info("Added new server-wide configuration option: \"{}\"", fullyQualifiedName);
                }

                @Override
                public void onDiffRemove(final String fullyQualifiedName) {
                    LOGGER.warn("Server-wide configuration option \"{}\" no longer exists and is now removed.", fullyQualifiedName);
                }

                @Override
                public void onFinishLoad(final GlobalConfiguration instance) {

                    postLoad(instance);
                }
            },
            Style.create()
                .literal("Aurora / Server configuration").endLine()
                .blank()
                .wordWrap("Server-wide region-engine settings for SourbyCraft.",
                    "The historical config/canvas-server.yml path is retained for existing servers.").endLine()
                .blank()
                .wordWrap("Use /aurora for service status and /sourbycraft config for file locations.",
                    "/sourbycraft reload re-reads supported settings; construction-cached options require restart.").endLine()
                .blank()
                .wordWrap("Messages, UI and updates belong to sourbycraft_global_config.toml.",
                    "Aurora service budgets and toggles belong to aurora.toml.").endLine()
                .compile(60)
        );
    }

    private static void postLoad(final GlobalConfiguration configuration) {
        INSTANCE = configuration;

        // validate the configuration so users don't end up doing a stupid
        Validator.validateObject(configuration);

        if (TickRegions.hasStarted()) {

            // if this is a reload, we may have things that need to be taken into effect now
            // for example, 1.8 combat delay configs may be updated, so we conduct updates

            final MinecraftServer server = MinecraftServer.getServer();
            final PlayerList playerList = server.getPlayerList();

            for (final ServerPlayer player : playerList.getPlayers()) {
                // update all info with player, covers 1.8 combat config and branding
                player.getBukkitEntity().taskScheduler.scheduleOrExecute((ServerPlayer entityPlayer) -> {
                    playerList.sendAllPlayerInfo(entityPlayer);
                    entityPlayer.connection.send(new ClientboundCustomPayloadPacket(new BrandPayload(server.getServerModName())));
                });
            }

            server.rebuildServerStatus();
        }
        else {

            // this is only for startup-specific things, and should not contain post actions
            // that should be run on reload too. anything for reload and startup should be below

            try {
                RandomGeneratorFactory.of("Xoroshiro128PlusPlus");
            } catch (final Throwable ignored) {
                broadcast("Canvas' faster random impl is not supported by your VM, falling back to legacy random", WARN);
                ENABLE_FASTER_RANDOM = false;
            }

            // SIMD actions
            try {
                SIMDDetection.isEnabled = SIMDDetection.canEnable(LOGGER);
            } catch (final Throwable thrown) {
                LOGGER.warn("Couldn't enable SIMD", thrown);
            }

            if (SIMDDetection.isEnabled) {
                LOGGER.info("SIMD operations detected as functional. Will replace some operations with faster versions.");
            }
            else {
                LOGGER.warn("SIMD operations are available for your server, but are not configured!");
                LOGGER.warn("To enable additional optimizations, add \"--add-modules=jdk.incubator.vector\" to your startup flags, BEFORE the \"-jar\".");
                LOGGER.warn("If you have already added this flag, then SIMD operations are not supported on your JVM or CPU.");
                LOGGER.warn("Debug: Java: {}, test run: {}", System.getProperty("java.version"), SIMDDetection.testRun);
            }

            final Path logsDirectoryPath = Path.of("logs");

            // start log cleaner, only at startup
            if (configuration.logs.enableLogCleaner && Files.exists(logsDirectoryPath)) {
                final MutableInt amountRemoved = new MutableInt(0);

                Util.removeDirectoryContentsIf(logsDirectoryPath.toFile(), (path) -> {
                    try {
                        final Instant lastModified = Files.getLastModifiedTime(path).toInstant();
                        // accept large units because servers may specify units larger than days
                        final TimeSpan loggerTimeSpan = TimeSpan.parse(configuration.logs.cleanerTimeSpan).acceptLargeUnits();
                        if (lastModified.isBefore(loggerTimeSpan.inPast()) && !path.getFileName().toString().equalsIgnoreCase("latest.log")) {
                            // the time the log file was modified is before the
                            // thresh, meaning it is older than the thresh set
                            amountRemoved.increment();
                            return true;
                        }
                    } catch (final IOException ioe) {
                        broadcast("Unable to determine if file " + path.getFileName() + " should be removed because: " + ioe.getMessage(), ERROR);
                    }
                    return false;
                });

                if (amountRemoved.intValue() > 0) {
                    broadcast("Log cleaner removed " + amountRemoved.intValue() + " old log files", INFO);
                }
            }

            // The /canvas dispatcher is removed by the existing feature patch. Operator views
            // are registered by SourbyCraftBootstrap under /aurora and /sourbycraft.
            broadcast("Aurora region settings loaded; use /aurora for status and configuration", INFO);
        }

        // we do not want to allow larger unit values, nobody should autosave in units larger than
        // days, like who tf would use time units like "1 week"??
        AUTOSAVE_SPAN.swapValue((_) -> TimeSpan.parse(configuration.autosave.autosaveFrequency).verifyIsntLargeUnit());

        broadcast("Server will autosave enabled selection every " + configuration.autosave.autosaveFrequency, INFO);
        broadcast("Using " + configuration.regionScheduler.defaultTickRate + " as default tick rate", INFO);
    }

    public static GlobalConfiguration getInstance() {
        return INSTANCE;
    }


    public static RandomSource createFastRandom() {
        return ENABLE_FASTER_RANDOM ? new FasterRandomSource(RandomSupport.generateUniqueSeed()) : new SimpleThreadUnsafeRandom(RandomSupport.generateUniqueSeed());
    }

    public static void broadcast(final String msg, final int severity) {
        if (TickRegions.hasStarted()) {
            final MutableComponent literal = Component.literal(msg);

            switch (severity) {
                case WARN -> literal.withStyle(ChatFormatting.YELLOW);
                case ERROR -> literal.withStyle(ChatFormatting.RED);
            }

            // players might be in the server, try and send msg to people with perms

            for (final ServerPlayer entityPlayer : MinecraftServer.getServer().getPlayerList().getPlayers()) {
                if (entityPlayer.getBukkitEntity().hasPermission(BROADCAST_PERMISSION)) {
                    entityPlayer.sendSystemMessage(literal);
                }
            }
        }

        // send to console
        switch (severity) {
            case INFO -> LOGGER.info(msg);
            case WARN -> LOGGER.warn(msg);
            case ERROR -> LOGGER.error(msg);
        }
    }

    /**
     * Saves the existing configuration from memory to disk
     */
    public void save() {
        save(CONFIG_PATH);
    }

    public RegionScheduler regionScheduler = new RegionScheduler();
    public static class RegionScheduler extends Part {

        {
            option("affinityScheduler")
                .docs(
                    "Configurations for the AFFINITY scheduler provided by Canvas. For these options to take effect,",
                    "change the \"threaded-regions.scheduler\" option in \"paper-global.yml\" to \"AFFINITY\""
                );
        }

        public AffinityScheduler affinityScheduler = new AffinityScheduler();
        public static class AffinityScheduler extends Part {

            {
                option("stealThresholdMillis")
                    .docs(
                        Style.wrap(
                            "The maximum amount of time, in milliseconds, a thread will delay the execution of a scheduled task",
                            "before allowing other threads to steal it for execution."
                        )
                        .blank()
                        .literal("Note: A smaller value reduces task deadline delays but increases potential task stealing between threads")
                    ).greaterThanOrEqualTo(0.0F);

                option("runTasksBufferMillis")
                    .docs(
                        Style.wrap(
                            "Buffer time (in milliseconds) before tick deadline to stop executing intermediate tasks.",
                            "Ensures runTick() can start on time, at the deadline."
                        )
                        .blank()
                        .literal("Default: 0.1ms, Higher is safer, lower means more work is done")
                    ).greaterThanOrEqualTo(0.0F);

                option("tickRegionAffinity")
                    .docs("Thread affinity for the AFFINITY scheduler provided by Canvas. By using this, you could pin the threads of region scheduler to cpu cores")
                    .greaterThanOrEqualTo(0.0F);
                option("enableAffinitySchedulerCpuAffinity").docs("Enables pinning threads of the AFFINITY region scheduler to cpu cores");
            }

            public long stealThresholdMillis = AffinitySchedulerThreadPool.DEFAULT_STEAL_THRESH_MILLIS;
            public double runTasksBufferMillis = AffinitySchedulerThreadPool.DEFAULT_RUN_TASKS_BUFFER_MILLIS;

            public int[] tickRegionAffinity = new int[0];
            public boolean enableAffinitySchedulerCpuAffinity = false;
        }

        {
            option("overloadedLogMillis")
                .docs(
                    "Amount of time between the end and next start of a region tick where the server will log a",
                    "warning that the scheduler is overloaded. Can help catch if you need to allocate more threads",
                    "or help identify deadline missing issues"
                ).greaterThan(0.0F);

            option("defaultTickRate")
                .docs(
                    "The default tick rate for the scheduler. Vanilla is 20, the game will run faster or slower depending on how you adjust this value.",
                    "Note this should really only be used for debugging purposes and for custom environments that require this change"
                ).greaterThan(0.0F);

            option("guardSeverity")
                .docs(
                    Style.wrap(
                        "Canvas introduces extra tick thread checks to help catch plugin issues. This determines how aggressive the new guards are"
                    ).defineEnum(GuardSeverity.class, (severity) -> switch (severity) {
                        case LOG -> "Just logs a warning in console, but continues the operation";
                        case THROW -> "Throws an exception, can crash the server. Good for ensuring correctness";
                        case SILENT -> "Doesn't say anything or do anything";
                    })
                );
        }

        public long overloadedLogMillis = 5_000L;
        public float defaultTickRate = 20.0F;
        // SourbyCraft - stabilize default: Canvas ships THROW ("Throws an exception, can crash the
        // server"), which is a correctness/dev default. On a real server a plugin doing an
        // off-region operation should be logged, not crash the whole server, so default to LOG
        // ("logs a warning in console, but continues the operation"). Operators can still set THROW
        // in config/canvas-server.yml for strict correctness testing.
        public GuardSeverity guardSeverity = GuardSeverity.LOG;

        public enum GuardSeverity {
            SILENT,
            LOG,
            THROW
        }

        {
            option("preventExcessiveVelocityMoveOutOfRegion").docs(
                "This option prevents the attempted movement of entities with excessive velocity from exceeding the region bounds",
                "by setting the velocity of the entity to 0 if it attempts to move outside of the region. Note this option does",
                "not take collisions into account, and it will calculate this from the raw velocity, which is a much stricter way",
                "to govern this safe guard. By disabling this, if the entity is still attempting to move out of region after applying",
                "collisions, a warning will show in console and the entity will instead be teleported to prevent the server from crashing."
            );
        }

        public boolean preventExcessiveVelocityMoveOutOfRegion = false;
    }

    public ChunkSystem chunkSystem = new ChunkSystem();
    public static class ChunkSystem extends Part {

        {
            option("fluidPostProcessingAlgorithm")
                .docs(
                    Style.wrap(
                        "The worldgen processes creates a lot of unnecessary fluid post-processing tasks,",
                        "which can overload the server and cause stuttering when generating new chunks.",
                        "Depending on the algorithm chosen, this can help reduce stutter and improve performance",
                        "when generating chunks"
                    ).defineEnum(FluidPostProcessingMode.class, (mode) -> switch (mode) {
                        case VANILLA -> "Normal post processing algorithm, everything is processed";
                        case DISABLED -> "Disables fluid post processing entirely";
                        case FILTERED -> "C2MEs algorithm to filter unnecessary post processing tasks";
                    })
                );
        }

        public FluidPostProcessingMode fluidPostProcessingAlgorithm = FluidPostProcessingMode.VANILLA;

        public enum FluidPostProcessingMode {
            VANILLA,
            DISABLED,
            FILTERED
        }

        {
            option("optimizeTreasureMapLocating")
                .docs(
                    "Treasure map locating is a very expensive operation, leading to most production servers",
                    "disabling it. This option tries to optimize the treasure map initial search to make this",
                    "less expensive on item creation"
                );
        }

        public boolean optimizeTreasureMapLocating = false;
    }

    // TODO - check these on minecraft updates
    public UpstreamFixes vanillaFixes = new UpstreamFixes();
    public static class UpstreamFixes extends Part {

        {
            stream((fieldName, option) -> {
                if (fieldName.startsWith("mc")
                    && fieldName.substring(2).chars().allMatch(Character::isDigit)) {
                    // this is a specific minecraft fix
                    option.docs(
                        Style.create()
                            .literal("https://bugs.mojang.com/browse/MC/issues/MC-" + fieldName.substring(2))
                    );
                }
            });

            option("mc261810").docs("Fixes low firework propulsion in the void");
            option("mc298464").docs("Fixes a memory leak related to Hoglin removal due to CHANGED_DIMENSION");
            option("mc223153").docs("Fixes blocks of raw copper using stone sounds instead of copper sounds");
            option("mc200418").docs("Fixes cured baby zombies staying as jockey variants");
            // NOTE: Marked as fixed but isn't; look at affected versions instead
            option("mc94054").docs("Fixes cave spiders and spiders with the small scale attribute spinning around when walking");
            option("mc245394").docs("Fixes raid horn blare sounds being controlled by the Friendly Creatures sound slider");
            option("mc227337").docs("Fixes explosion sounds and particles not being produced when a shulker bullet hits an entity");
            option("mc221257").docs("Fixes shulker bullets not producing bubble particles when moving through water");
            option("mc206922").docs("Fixes item drops by entities that were killed by lightning instantly disappearing");
            option("mc155509").docs("Fixes dying puffed pufferfishes still stinging players");
            option("mc132878").docs("Fixes armor stands destroyed by explosions/lava/fire not producing particles");
            option("mc121706").docs("Fixes skeletons and illusioners not looking up/down at their target while strafing");
            option("mc119754").docs("Fixes elytra firework boosts continuing while in spectator mode");
            option("mc100991").docs("Fixes killing entities with a fishing rod not counting as a kill");
            option("mc30391").docs("Fixes chickens, blazes and withers emitting particles during landing despite falling slowly");
            option("mc183990").docs("Fixes group AI of some mobs breaking when their target dies");
            option("mc136249").docs("Fixes wearing enchanted boots with depth strider decreasing the strength of the riptide enchantment");
        }

        public boolean mc261810 = false;
        public boolean mc298464 = false;
        public boolean mc223153 = false;
        public boolean mc200418 = false;
        public boolean mc94054 = false;
        public boolean mc245394 = false;
        public boolean mc227337 = false;
        public boolean mc221257 = false;
        public boolean mc206922 = false;
        public boolean mc155509 = false;
        public boolean mc132878 = false;
        public boolean mc121706 = false;
        public boolean mc119754 = false;
        public boolean mc100991 = false;
        public boolean mc30391 = false;
        public boolean mc183990 = false;
        public boolean mc136249 = false;
    }

    public Networking networking = new Networking();
    public static class Networking extends Part {

        {
            option("filterVelocityPacket")
                .docs(
                    "The ClientboundSetEntityMotionPacket, also known as the entity velocity packet, can often",
                    "consume major amounts of network usage, often being up to 60% on large production servers",
                    "This option filters the unnecessary packets sent, while still maintaining Vanilla visual effects"
                );
            option("filterMovePackets").docs("Filters useless move packets that don't need to be sent");

            option("alternativePlayerListTick").docs("Splits players into buckets to be spread evenly across the playerlist tick");
            option("playerInfoSendInterval")
                .docs(
                    "If alternative playerlist tick is enabled, this is the interval in ticks for how often",
                    "each bucket will be ticked"
                ).greaterThan(0.0F);
            option("purpurAlternativeKeepalive")
                .docs(
                    Style.create()
                        .wordWrap(
                            "Uses a different approach to keepalive ping timeouts.",
                            "Enabling this sends a keepalive packet once per second to a player, and only kicks for timeout if none of them were responded to in 30 seconds.",
                            "Responding to any of them in any order will keep the player connected.")
                        .blank()
                        .wordWrap("AKA, it won't kick your players because one packet gets dropped somewhere along the lines"));

            option("flushLocationWhileKnockback")
                .docs("Derived from Leaf, this synchronizes the player immediately when knocked back");
        }

        public boolean filterVelocityPacket = false;
        public boolean filterMovePackets = false;
        public boolean alternativePlayerListTick = false;
        public int playerInfoSendInterval = 600;
        public boolean purpurAlternativeKeepalive = false;

        // Originally from Leaf: https://github.com/Winds-Studio/Leaf/blob/58a4a9cb7994474e63ba49205cd21e89f8dacc9a/leaf-server/minecraft-patches/features/0216-Flush-location-while-knockback.patch
        // License described in Leaf-Flush-location-while-knockback.patch
        public boolean flushLocationWhileKnockback = false;
    }

    {
        option("serverModName").docs("The server mod name displayed in server listings and client info").word();

        option("displayWorldLoadScreenForCrossRegionTransfers")
            .docs(
                "Folia's portaling rewrite makes the world loading screen not display on the client properly, and",
                "instead shows an empty void. With this enabled, Canvas will display the proper world loading screen"
            );
        option("cacheMinecraft2BukkitEntityTypeConversion").docs("Whether to cache expensive CraftEntityType#minecraftToBukkit call");
        option("tileEntitySnapshotCreation").docs("Enables creation of tile entity snapshots on retrieving blockstates");
    }

    public String serverModName = ServerBuildInfo.buildInfo().brandName();

    public boolean displayWorldLoadScreenForCrossRegionTransfers = true;

    public boolean cacheMinecraft2BukkitEntityTypeConversion = false;
    public boolean tileEntitySnapshotCreation = false;

    public PurpurContainers purpurContainers = new PurpurContainers();
    public static class PurpurContainers extends Part {

        {
            option("barrelRows").docs("The amount of rows for the barrel block").between(1, 6);
            option("enderChestSixRows").docs("Whether to use 6 rows for the player ender chest, rather than the normal 3");
            option("enderChestPermissionRows")
                .docs(
                    Style.wrap("Whether to use a permission based system for defining the size of ender chests per player")
                        .literal("Valid permissions").endLine()
                        .literal(" - purpur.enderchest.rows.six").endLine()
                        .literal(" - purpur.enderchest.rows.five").endLine()
                        .literal(" - purpur.enderchest.rows.four").endLine()
                        .literal(" - purpur.enderchest.rows.three").endLine()
                        .literal(" - purpur.enderchest.rows.two").endLine()
                        .literal(" - purpur.enderchest.rows.one").endLine()
                );
            option("enderChestPersistHiddenRows").docs("Whether items should remain stored in slots, even if those slots become inaccessible through permissions");
        }

        public int barrelRows = 3;
        public boolean enderChestSixRows = false;
        public boolean enderChestPermissionRows = false;
        public boolean enderChestPersistHiddenRows = true;
    }

    @Undocumented("Doesn't require docs.")
    public boolean blacklistNonPlayerEntitiesFromEnteringNetherPortals = false;
    @Undocumented("Doesn't require docs.")
    public boolean blacklistNonPlayerEntitiesFromEnteringEndPortals = false;
    @Undocumented("Doesn't require docs.")
    public boolean blacklistNonPlayerEntitiesFromEnteringGatewayPortals = false;

    public Chat chat = new Chat();
    public static class Chat extends Part {

        {
            option("disableChatReporting").docs("Disables Minecraft chat signing to prevent player chat reporting");
            option("disableChatVerificationOrder").docs("Disables Minecraft chat verification ordering");
        }

        public boolean disableChatReporting = false;
        public boolean disableChatVerificationOrder = false;
    }

    public Logs logs = new Logs();

    public static class Logs extends Part {

        {
            option("enableLogCleaner").docs("Auto-removes old log files from the \"logs\" directory");
            option("cleanerTimeSpan").docs("The amount of the time since the log file was last edited until it will be deleted");
            option("logEnderPearlRewriteActions").docs("Logs when a pearl is saved or loaded from Canvas' pearl save rewrite");
        }

        private boolean enableLogCleaner = false;
        private String cleanerTimeSpan = "30d";
        // SourbyCraft - stabilize default: this is a debug-logging flag that logs on every ender
        // pearl save/load from Canvas' pearl-save rewrite (frequent on an active server = console
        // spam). Default it off; operators can flip it on in config/canvas-server.yml to debug.
        public boolean logEnderPearlRewriteActions = false;
    }

    public EnchantCommand enchantCommand = new EnchantCommand();
    public static class EnchantCommand extends Part {

        {
            option("uncapMaxLevel").docs("Uncaps the max level, allowing you to enchant to any level, even beyond the max");
            option("allowEnchantsOnUnsupportedItems").docs("Allows setting enchants on items that normally do not support that enchantment");
            option("allowEnchantingWithIncompatibleEnchants").docs("Allows setting enchants on items with incompatible enchants. e.g. Protection & Blast Protection");
        }

        public boolean uncapMaxLevel = false;
        public boolean allowEnchantsOnUnsupportedItems = false;
        public boolean allowEnchantingWithIncompatibleEnchants = false;
    }

    {
        option("disableLocatorBarInAllWorlds").docs("Disables the locator bar globally, removing the need to disable it using gamerules per-world");
    }

    public boolean disableLocatorBarInAllWorlds = false;

    {
        option("autosave").docs(
            "Folia breaks a lot of autosave features. Canvas restores these,",
            "and this section allows more specific configuration of autosave functionalities"
        );
    }

    public Autosave autosave = new Autosave();

    @Undocumented("Doesn't require docs.")
    public static class Autosave extends Part {

        {
            option("autosaveFrequency").docs("The time frequency of how often to autosave the enabled selection. Default is 5 minutes to match upstream");
        }

        private String autosaveFrequency = "5m";

        public boolean autosaveScoreboards = true;
        public boolean autosaveStopwatches = true;
        public boolean autosavePearls = true;
        public boolean autosaveCustomBossEvents = true;
        public boolean autosaveTime = true;
        public boolean autosaveMaps = true;
        public boolean autosaveWeather = true;
        public boolean autosaveGamerules = true;
        public boolean autosavePlayers = true;
    }
}
