package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The part of a player's state that belongs to a profile.
 *
 * <p>The core is Bukkit-free: item stacks stay as opaque encoded bytes produced by the platform
 * adapter ({@link dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter} or the built-in Paper
 * adapter), so the codec can be unit-tested without a server and no untrusted data is ever turned
 * into an object graph. Each category is included only when the isolation settings request it; an
 * excluded category is empty and is not applied back.</p>
 *
 * <p>What is <strong>not</strong> captured by default: advancements, statistics, recipe books,
 * plugin-owned inventories, and anything a plugin stores outside this record. Those require a
 * registered adapter with its own version.</p>
 *
 * @param schema codec schema version
 * @param inventory main inventory slots (opaque item bytes, slot = index in the hotbar and storage,
 *     {@code 0..35}); a snapshot written before Build 50 also holds armor in slots {@code 36..39} and
 *     the off-hand in slot {@code 40}
 * @param armor armor slots
 * @param offhand off-hand slot
 * @param enderChest ender-chest slots
 * @param level experience level
 * @param experience experience progress {@code 0..1}
 * @param health current health
 * @param food food level
 * @param saturation saturation
 * @param effects active potion effects
 * @param spawn the respawn point (bed, anchor or one a plugin set), as stored without validating it
 * @param returnPoint the player's location when the state was captured; a switch moves them there
 * @param adapters plugin adapter key ({@code id:version}) to its bytes
 */
public record ProfileState(int schema, List<Item> inventory, List<Item> armor, List<Item> offhand,
                           List<Item> enderChest, int level, float experience, double health, int food,
                           float saturation, List<Effect> effects, Location spawn, Location returnPoint,
                           Map<String, byte[]> adapters) {

    public static final int SCHEMA = 1;
    public static final int MAX_ITEMS = 256;
    public static final int MAX_EFFECTS = 64;
    public static final int MAX_ADAPTERS = 32;
    /** Longest adapter key ({@code id:version}). */
    public static final int MAX_ADAPTER_KEY = 64;
    /** Bytes one adapter entry may hold. */
    public static final int MAX_ADAPTER_BYTES = 2 * 1024 * 1024;
    /**
     * Bytes all adapter entries of one state may hold together, so that adapters alone never push a
     * state past the 8 MiB snapshot limit; the rest is left for items.
     */
    public static final int MAX_ADAPTER_TOTAL = 6 * 1024 * 1024;
    /** Duration of an effect that does not run out ({@code PotionEffect.INFINITE_DURATION}). */
    public static final int INFINITE_DURATION = -1;
    /** Highest amplifier the server stores for an effect (vanilla saves it as an unsigned byte). */
    public static final int MAX_AMPLIFIER = 255;

    /** One opaque encoded item at a slot. The bytes are never interpreted by the core. */
    public record Item(int slot, byte[] data) {
        public Item {
            if (slot < 0 || slot > 4095) throw new IllegalArgumentException("slot " + slot);
            if (data == null || data.length == 0 || data.length > 1_048_576) {
                throw new IllegalArgumentException("item data must be 1-1048576 bytes");
            }
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return this.data.clone();
        }
    }

    /**
     * One potion effect, with the type key instead of a registry object. The duration is in ticks, or
     * {@link #INFINITE_DURATION} for an effect that does not run out; the amplifier is
     * {@code 0..}{@link #MAX_AMPLIFIER}.
     */
    public record Effect(String typeKey, int durationTicks, int amplifier, boolean ambient, boolean particles,
                         boolean icon) {
        public Effect {
            Objects.requireNonNull(typeKey, "typeKey");
            if (!typeKey.matches("[a-z0-9_.:/-]{1,64}")) throw new IllegalArgumentException("effect key: " + typeKey);
            if (durationTicks < INFINITE_DURATION) throw new IllegalArgumentException("duration " + durationTicks);
            if (amplifier < 0 || amplifier > MAX_AMPLIFIER) throw new IllegalArgumentException("amplifier " + amplifier);
        }
    }

    /** A location in an AWF world. */
    public record Location(String world, double x, double y, double z, float yaw, float pitch) {
        public Location {
            Objects.requireNonNull(world, "world");
            if (!world.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("world: " + world);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("location is not finite");
            }
        }
    }

    public ProfileState {
        if (schema < 1) throw new IllegalArgumentException("schema " + schema);
        inventory = copyItems(inventory);
        armor = copyItems(armor);
        offhand = copyItems(offhand);
        enderChest = copyItems(enderChest);
        effects = List.copyOf(effects == null ? List.of() : effects);
        if (effects.size() > MAX_EFFECTS) throw new IllegalArgumentException("too many effects");
        if (adapters == null) adapters = Map.of();
        if (adapters.size() > MAX_ADAPTERS) throw new IllegalArgumentException("too many adapters");
        final Map<String, byte[]> copy = new LinkedHashMap<>();
        adapters.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z0-9_.:-]{1," + MAX_ADAPTER_KEY + "}")) {
                throw new IllegalArgumentException("adapter id: " + key);
            }
            if (value == null || value.length > MAX_ADAPTER_BYTES) throw new IllegalArgumentException("adapter state");
            copy.put(key, value.clone());
        });
        adapters = java.util.Collections.unmodifiableMap(copy);
        if (experience < 0.0f || experience > 1.0f) throw new IllegalArgumentException("experience " + experience);
        if (food < 0 || food > 20) throw new IllegalArgumentException("food " + food);
        if (saturation < 0.0f) throw new IllegalArgumentException("saturation " + saturation);
        if (!Double.isFinite(health)) throw new IllegalArgumentException("health");
    }

    private static List<Item> copyItems(final List<Item> items) {
        final List<Item> copy = List.copyOf(items == null ? List.of() : items);
        if (copy.size() > MAX_ITEMS) throw new IllegalArgumentException("too many items");
        return copy;
    }

    @Override
    public Map<String, byte[]> adapters() {
        final Map<String, byte[]> copy = new LinkedHashMap<>();
        this.adapters.forEach((key, value) -> copy.put(key, value.clone()));
        return java.util.Collections.unmodifiableMap(copy);
    }

    /** This state with its adapter entries replaced. */
    public ProfileState withAdapters(final Map<String, byte[]> replacement) {
        return new ProfileState(this.schema, this.inventory, this.armor, this.offhand, this.enderChest, this.level,
            this.experience, this.health, this.food, this.saturation, this.effects, this.spawn, this.returnPoint,
            replacement);
    }

    public static ProfileState empty() {
        return new ProfileState(SCHEMA, List.of(), List.of(), List.of(), List.of(), 0, 0.0f, 20.0, 20, 5.0f, List.of(),
            null, null, Map.of());
    }

    public boolean isEmpty() {
        return this.inventory.isEmpty() && this.armor.isEmpty() && this.offhand.isEmpty() && this.enderChest.isEmpty()
            && this.level == 0 && this.experience == 0.0f && this.effects.isEmpty() && this.adapters.isEmpty();
    }
}
