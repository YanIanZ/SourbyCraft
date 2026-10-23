package dev.iyanz.sourbycraft.awf.multiprofile;

/**
 * Which categories a profile owns. A category that is off is neither captured nor applied, so the
 * data stays in the server's vanilla player file; that is a deliberate, documented limitation rather
 * than a silent half-isolation.
 */
public record ProfileIsolation(boolean inventory, boolean armor, boolean enderChest, boolean experience,
                               boolean health, boolean food, boolean potionEffects, boolean location) {

    public static final ProfileIsolation ALL =
        new ProfileIsolation(true, true, true, true, true, true, true, true);

    /** What Build 49's default configuration enables; every category is captured. */
    public static final ProfileIsolation DEFAULT = ALL;

    /** Captures nothing; a "profile" then carries only the active-profile reference. */
    public static final ProfileIsolation NONE =
        new ProfileIsolation(false, false, false, false, false, false, false, false);

    public static final String INVENTORY_KEY = "aurora.awf.multiprofile.isolation.inventory";
    public static final String ARMOR_KEY = "aurora.awf.multiprofile.isolation.armor";
    public static final String ENDER_CHEST_KEY = "aurora.awf.multiprofile.isolation.ender-chest";
    public static final String EXPERIENCE_KEY = "aurora.awf.multiprofile.isolation.experience";
    public static final String HEALTH_KEY = "aurora.awf.multiprofile.isolation.health";
    public static final String FOOD_KEY = "aurora.awf.multiprofile.isolation.food";
    public static final String POTION_EFFECTS_KEY = "aurora.awf.multiprofile.isolation.potion-effects";
    public static final String LOCATION_KEY = "aurora.awf.multiprofile.isolation.location";

    public java.util.List<String> keys() {
        return java.util.List.of(INVENTORY_KEY, ARMOR_KEY, ENDER_CHEST_KEY, EXPERIENCE_KEY, HEALTH_KEY, FOOD_KEY,
            POTION_EFFECTS_KEY, LOCATION_KEY);
    }

    /** The subset of {@code keys()} that this setting disables, for diagnostics. */
    public java.util.List<String> disabledKeys() {
        final java.util.List<String> disabled = new java.util.ArrayList<>();
        if (!this.inventory) disabled.add(INVENTORY_KEY);
        if (!this.armor) disabled.add(ARMOR_KEY);
        if (!this.enderChest) disabled.add(ENDER_CHEST_KEY);
        if (!this.experience) disabled.add(EXPERIENCE_KEY);
        if (!this.health) disabled.add(HEALTH_KEY);
        if (!this.food) disabled.add(FOOD_KEY);
        if (!this.potionEffects) disabled.add(POTION_EFFECTS_KEY);
        if (!this.location) disabled.add(LOCATION_KEY);
        return java.util.List.copyOf(disabled);
    }
}
