/*
 * This file is part of Kaiiju (https://github.com/KaiijuMC/Kaiiju)
 *
 * Kaiiju is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Kaiiju is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Kaiiju. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.kaiijumc.kaiiju;

import com.google.common.base.Throwables;
import com.mojang.logging.LogUtils;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

@SuppressWarnings("unused")
public class KaiijuEntityLimits {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final File CONFIG_FOLDER = new File("sourbycraft_config");

    protected static final String HEADER =
            "Per-region entity limits for SourbyCraft.\n"
                    + "If there are more of a particular entity type in a region than limit, entity ticking will be throttled.\n"
                    + "Example: for Wither limit 100 & 300 Withers in a region -> 100 Withers tick every tick & every Wither ticks every 3 ticks.\n"
                    + "Available entities: GlowSquid, Ambient, Bat, Animal, Bee, Cat, Chicken, Cod, Cow, Dolphin, Fish, FishSchool, Fox, Golem, IronGolem, "
                    + "MushroomCow, Ocelot, Panda, Parrot, Perchable, Pig, PolarBear, PufferFish, Rabbit, Salmon, Sheep, Snowman, Squid, TropicalFish, Turtle, "
                    + "WaterAnimal, Wolf, Allay, Axolotl, Camel, Frog, Tadpole, Goat, Horse, HorseAbstract, HorseChestedAbstract, HorseDonkey, HorseMule, "
                    + "HorseSkeleton, HorseZombie, Llama, LlamaTrader, Sniffer, EnderCrystal, EnderDragon, Wither, ArmorStand, Hanging, ItemFrame, Leash, "
                    + "Painting, GlowItemFrame, FallingBlock, Item, TNTPrimed, Blaze, CaveSpider, Creeper, Drowned, Enderman, Endermite, Evoker, Ghast, "
                    + "GiantZombie, Guardian, GuardianElder, IllagerAbstract, IllagerIllusioner, IllagerWizard, MagmaCube, Monster, MonsterPatrolling, Phantom, "
                    + "ZombifiedPiglin, Pillager, Ravager, Shulker, Silverfish, Skeleton, SkeletonAbstract, SkeletonStray, SkeletonWither, Slime, Spider, Strider, Vex, "
                    + "Vindicator, Witch, Zoglin, Zombie, ZombieHusk, ZombieVillager, Hoglin, Piglin, PiglinAbstract, PiglinBrute, Warden, Villager, "
                    + "VillagerTrader, Arrow, DragonFireball, Egg, EnderPearl, EnderSignal, EvokerFangs, Fireball, FireballFireball, Fireworks, FishingHook, "
                    + "LargeFireball, LlamaSpit, Potion, Projectile, ProjectileThrowable, ShulkerBullet, SmallFireball, Snowball, SpectralArrow, ThrownExpBottle, "
                    + "ThrownTrident, TippedArrow, WitherSkull, Raider, ChestBoat, Boat, MinecartAbstract, MinecartChest, MinecartCommandBlock, MinecartContainer, "
                    + "MinecartFurnace, MinecartHopper, MinecartMobSpawner, MinecartRideable, MinecartTNT\n";
    protected static final File ENTITY_LIMITS_FILE = new File(CONFIG_FOLDER, "sourby_entity_limits.yml");
    public static YamlConfiguration entityLimitsConfig;
    public static boolean enabled = false;

    // SourbyCraft: runtime-tunable global scale applied to every per-type tick cap. The base uses
    // the raw per-type `limit` from sourby_entity_limits.yml; this multiplier lets the SourbyCraft
    // perf-engine scale ALL caps down together per-tier under load (fewer entities tick per tick =>
    // cheaper) without the operator editing the yml. Default 1.0 == the exact base behaviour, so at
    // rest / GREEN there is ZERO change. Only YELLOW+ tiers push a smaller value (e.g. 0.75 RED,
    // 0.5 EMERGENCY) via KnobEnforcer. Applied by KaiijuEntityThrottler#scaledLimit, which floors
    // the scaled cap at 1 so a type is never fully starved. Only takes effect while `enabled` is on.
    public static volatile double limitScale = 1.0D;

    /** Effective per-type tick cap after applying {@link #limitScale}, floored at 1. */
    public static int scaledLimit(int baseLimit) {
        final double scale = limitScale;
        if (scale >= 1.0D) return baseLimit; // fast path: default / no down-scaling => unchanged
        return Math.max(1, (int) Math.round(baseLimit * scale));
    }

    protected static Map<Class<? extends Entity>, EntityLimit> entityLimits;

    static final String ENTITY_PREFIX = "Entity";

    public static void init() {
        init(true);
    }

    private static void init(boolean setup) {
        entityLimitsConfig = new YamlConfiguration();

        if (ENTITY_LIMITS_FILE.exists()) {
            try {
                entityLimitsConfig.load(ENTITY_LIMITS_FILE);
            } catch (InvalidConfigurationException ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Could not load sourby_entity_limits.yml, please correct your syntax errors", ex);
                throw Throwables.propagate(ex);
            } catch (IOException ignore) {
            }
        } else {
            if (setup) {
                entityLimitsConfig.options().header(HEADER);
                entityLimitsConfig.options().copyDefaults(true);
                entityLimitsConfig.set("enabled", enabled);
                entityLimitsConfig.set("Axolotl.limit", 1000);
                entityLimitsConfig.set("Axolotl.removal", 2000);
                try {
                    entityLimitsConfig.save(ENTITY_LIMITS_FILE);
                } catch (IOException ex) {
                    Bukkit.getLogger().log(Level.SEVERE, "Could not save " + ENTITY_LIMITS_FILE, ex);
                }
            }
        }

        enabled = entityLimitsConfig.getBoolean("enabled");

        entityLimits = new Object2ObjectOpenHashMap<>();
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages("net.minecraft.world.entity").scan()) {
            Map<String, ClassInfo> entityClasses = new HashMap<>();
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                Class<?> entityClass = Class.forName(classInfo.getName());
                if (Entity.class.isAssignableFrom(entityClass)) {
                    String entityName = extractEntityName(entityClass.getSimpleName());
                    entityClasses.put(entityName, classInfo);
                }
            }

            for (String key : entityLimitsConfig.getKeys(false)) {
                if (key.equals("enabled")) {
                    continue;
                }

                if (!entityClasses.containsKey(key)) {
                    LOGGER.error("Unknown entity '" + key + "' in sourby_entity_limits.yml, skipping");
                    continue;
                }
                int limit = entityLimitsConfig.getInt(key + ".limit");
                int removal = entityLimitsConfig.getInt(key + ".removal");

                if (limit < 1) {
                    LOGGER.error(key + " has a limit less than the minimum of 1, ignoring");
                    continue;
                }
                if (removal <= limit && removal != -1) {
                    LOGGER.error(key + " has a removal limit that is less than or equal to its limit, setting removal to limit * 10");
                    removal = limit * 10;
                }

                entityLimits.put((Class<? extends Entity>) Class.forName(entityClasses.get(key).getName()), new EntityLimit(limit, removal));
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static EntityLimit getEntityLimit(Entity entity) {
        return entityLimits.get(entity.getClass());
    }

    private static String extractEntityName(String input) {
        int prefixLength = ENTITY_PREFIX.length();

        if (input.length() <= prefixLength || !input.startsWith(ENTITY_PREFIX)) {
            return input;
        } else {
            return input.substring(prefixLength);
        }
    }

    public record EntityLimit(int limit, int removal) {
        @Override
        public String toString() {
            return "EntityLimit{limit=" + limit + ", removal=" + removal + "}";
        }
    }
}
