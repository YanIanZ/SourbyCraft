package dev.iyanz.sourbycraft.console;

import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * {@code %scLogger}: the logger name as a short tag, the third column of the console line and the
 * bracketed tag of the file line in {@code log4j2.xml} (paper feature patch 0016).
 *
 * <p>Server-internal loggers are named after their classes, either fully qualified
 * ({@code LoggerFactory.getLogger(Class)}, e.g. {@code oshi.software.os.mac.MacOperatingSystem})
 * or by simple class name (Mojang's {@code LogUtils.getClassLogger()}, e.g.
 * {@code PluginInitializerManager}, {@code TickRegions}). This converter folds every internal
 * namespace, and every known internal simple class name, onto one of five tags and prints every
 * other name exactly as it is, so a plugin or library logger (including dotted names such as
 * {@code My.Plugin} or {@code com.example.plugin.Main}) keeps its identity:
 * <ul>
 *   <li>{@code SourbyCraft}: {@code dev.iyanz.sourbycraft.*} and the owned area loggers
 *       {@code SourbyCraft/<Area>};</li>
 *   <li>{@code Aurora}: {@code dev.iyanz.aurora.*}, the region-threading engine's
 *       {@code io.papermc.paper.threadedregions.*}, and the engine classes that log by simple
 *       name ({@link #ENGINE_CLASSES});</li>
 *   <li>{@code Minecraft}: {@code net.minecraft.*}, {@code com.mojang.*}, the {@code Minecraft}
 *       logger and the root logger;</li>
 *   <li>{@code Paper}: {@code io.papermc.*}, {@code ca.spottedleaf.*},
 *       {@code org.bukkit.craftbukkit.*}, {@code com.destroystokyo.paper.*}, {@code org.spigotmc.*},
 *       and the Paper classes that log by simple name ({@link #PAPER_CLASSES});</li>
 *   <li>{@code OSHI}: {@code oshi.*};</li>
 *   <li>{@code com.sk89q.*} and {@code ru.tehkode.*} (plugins that bypass the plugin logger, the
 *       names Paper used to print without a prefix) print their simple class name;</li>
 *   <li>anything else, and every other name without a dot, is printed unchanged.</li>
 * </ul>
 * The simple-name tables list the classes in the materialized Paper, Minecraft and engine sources
 * that call {@code LogUtils.getClassLogger()} or name their logger literally (2026-10-22);
 * {@code StartupLogLayoutTest} scans those sources and fails when a class is missing here. The
 * region-scheduler class names appear only as log tags to fold; this converter calls nothing.
 *
 * <p>Options: {@code %scLogger{console}} pads the tag to {@value #COLUMN} characters so the
 * message column lines up, and colours the {@code SourbyCraft} and {@code Aurora} tags, but only
 * on a real terminal ({@link ConsolePalette#terminal()}) and only below WARN, where
 * {@code %scHighlight} does not already colour the message. {@code %scLogger{file}} prints
 * {@code [tag]}, the shape log analysers expect in {@code logs/latest.log}. Without an option the
 * bare tag is printed. Longer tags are never cut: a plugin's name is its identity. Lookups are a
 * prefix scan over a short table plus one hash lookup; nothing is allocated per event.
 *
 * <p>Compiled from the {@code log4jPlugins} source set, so the annotation processor lists it in
 * {@code Log4j2Plugins.dat} and it is available when log4j first reads {@code log4j2.xml}.
 */
@Plugin(name = "SourbyCraftLoggerConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"scLogger"})
public final class SourbyCraftLoggerConverter extends LogEventPatternConverter {

    /** Width of the console tag column: {@code SourbyCraft} plus one space. */
    static final int COLUMN = 12;

    static final String BRAND = "SourbyCraft";
    static final String ENGINE = "Aurora";
    static final String MINECRAFT = "Minecraft";
    static final String PAPER = "Paper";
    static final String OSHI = "OSHI";

    /**
     * Known internal namespace prefix to tag, first match wins. An empty tag means the simple
     * class name. Unlisted names are never changed.
     */
    private static final String[][] ALIASES = {
        {"dev.iyanz.sourbycraft.", BRAND},
        {"dev.iyanz.aurora.", ENGINE},
        {"io.papermc.paper.threadedregions.", ENGINE},
        {"net.minecraft.", MINECRAFT},
        {"com.mojang.", MINECRAFT},
        {"io.papermc.", PAPER},
        {"ca.spottedleaf.", PAPER},
        {"org.bukkit.craftbukkit.", PAPER},
        {"com.destroystokyo.paper.", PAPER},
        {"org.spigotmc.", PAPER},
        {"oshi.", OSHI},
        {"com.sk89q.", ""},
        {"ru.tehkode.", ""},
    };

    /** Engine (region threading, engine configuration, spark pinning) classes that log by simple name or literal. */
    static final String[] ENGINE_CLASSES = {
        "AuroraFileMigration", "ConfigurationProvider", "PearlsMigration",
        "FoliaAsyncScheduler", "FoliaWatchdogThread", "RegionizedServer", "RegionizedWorldData", "RegionShutdownThread",
        "TickRegions", "TickRegionScheduler", "WorldShutdownThread",
        "Scheduler", "SparkRegionPinner", "SparkGlobalPinner",
    };

    /** Paper classes that log by simple name or literal. */
    static final String[] PAPER_CLASSES = {
        "BootstrapProviderStorage", "ChunkHolderManager", "ChunkTaskScheduler", "Configurations", "CraftPlayer",
        "DirectoryProviderSource", "EnderPearls", "EntrypointUtil", "EnumValueSerializer", "FeatureSeedsGeneration",
        "FileArrayProviderSource", "FileProviderSource", "HorriblePlayerLoginEventHack", "IntOr",
        "LegacyCraftBukkitWorldMigration", "LegacyPaperConfig", "LegacyPluginLoadingStrategy", "LoadOrderTree",
        "MapSerializer", "MergeMap", "ModernPluginLoadingStrategy", "MoonriseCommon",
        "MultiRuntimePluginProviderStorage", "PaperConfigurations", "PaperConfigurationTask", "PaperDatapackRegistrar",
        "PaperFilledProfileCache", "PaperVersionFetcher", "PluginFlagProviderSource", "PluginInitializerManager",
        "ServerboundPacketClassSerializer", "ServerPluginProviderStorage", "SimpleProviderStorage", "SimpleRegionStorage",
        "SpigotLibraryLoader", "VanillaWorldMigration", "WorldConfiguration", "WorldFolderMigration", "WorldMigrationSupport",
    };

    private static final Map<String, String> SIMPLE_NAMES = new HashMap<>();

    static {
        for (final String name : ENGINE_CLASSES) {
            SIMPLE_NAMES.put(name, ENGINE);
        }
        for (final String name : PAPER_CLASSES) {
            SIMPLE_NAMES.put(name, PAPER);
        }
    }

    private enum Mode { PLAIN, CONSOLE, FILE }

    private final Mode mode;

    private SourbyCraftLoggerConverter(final String[] options) {
        super("scLogger", "scLogger");
        final String option = options != null && options.length > 0 && options[0] != null ? options[0] : "";
        this.mode = "console".equalsIgnoreCase(option) ? Mode.CONSOLE
            : "file".equalsIgnoreCase(option) ? Mode.FILE : Mode.PLAIN;
    }

    public static SourbyCraftLoggerConverter newInstance(final String[] options) {
        return new SourbyCraftLoggerConverter(options);
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final String tag = tag(event.getLoggerName());
        switch (this.mode) {
            case FILE -> toAppendTo.append('[').append(tag).append(']');
            case PLAIN -> toAppendTo.append(tag);
            case CONSOLE -> {
                final String colour = colour(tag, event.getLevel());
                if (colour != null) {
                    toAppendTo.append(colour).append(tag).append(ConsolePalette.RESET);
                } else {
                    toAppendTo.append(tag);
                }
                for (int i = tag.length(); i < COLUMN; i++) {
                    toAppendTo.append(' ');
                }
            }
        }
    }

    /** The tag for a logger name; the root logger and {@code Minecraft} are {@value #MINECRAFT}. */
    static String tag(final String logger) {
        if (logger == null || logger.isEmpty() || logger.equals(MINECRAFT)) {
            return MINECRAFT;
        }
        if (logger.indexOf('.') < 0) {
            if (logger.startsWith("SourbyCraft/")) {
                return BRAND;
            }
            final String known = SIMPLE_NAMES.get(logger);
            return known != null ? known : logger;
        }
        for (final String[] alias : ALIASES) {
            if (logger.startsWith(alias[0])) {
                return alias[1].isEmpty() ? simpleName(logger) : alias[1];
            }
        }
        return logger;
    }

    private static String colour(final String tag, final Level level) {
        if (level != null && level.isMoreSpecificThan(Level.WARN)) {
            return null; // %scHighlight colours the message of WARN/ERROR lines
        }
        final String colour = tag.equals(BRAND) ? ConsolePalette.CYAN : tag.equals(ENGINE) ? ConsolePalette.VIOLET : null;
        return colour != null && ConsolePalette.terminal() ? colour : null;
    }

    /** Segment after the last dot, without a {@code $Inner} suffix; the whole name if that is empty. */
    private static String simpleName(final String logger) {
        String simple = logger.substring(logger.lastIndexOf('.') + 1);
        final int inner = simple.indexOf('$');
        if (inner > 0) {
            simple = simple.substring(0, inner);
        }
        return simple.isEmpty() ? logger : simple;
    }
}
