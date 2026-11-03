package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Locale;

/**
 * A proposed gameplay rule classification, following Hypixel SkyBlock's published profile types.
 * Not wired yet into the 26.2 engine; enum descriptions express intended policy only.
 *
 * <p>The stored {@code type} stays a free consumer namespace ({@code classic}, {@code ironman}, a
 * plugin's own name); this enum classifies it into a known rule set. An unknown namespace maps to
 * {@link #CUSTOM} with no restrictions, without registering or enforcing rules for that type.</p>
 */
public enum ProfileType {

    /** Normal profile: every feature available. */
    CLASSIC(true, true, true, true, false),
    /** No trading, auction or bazaar; co-op allowed. */
    IRONMAN(false, false, false, true, false),
    /** One island, no portals; no trading, auction or bazaar. */
    STRANDED(false, false, false, true, false),
    /** Seasonal profile: temporary by policy, archived rather than deleted at the end. */
    BINGO(true, true, true, true, true),
    /** A consumer-defined type with no built-in restrictions. */
    CUSTOM(true, true, true, true, false);

    private final boolean trading;
    private final boolean auction;
    private final boolean bazaar;
    private final boolean coop;
    private final boolean seasonal;

    ProfileType(final boolean trading, final boolean auction, final boolean bazaar, final boolean coop,
                final boolean seasonal) {
        this.trading = trading;
        this.auction = auction;
        this.bazaar = bazaar;
        this.coop = coop;
        this.seasonal = seasonal;
    }

    /** Maps a stored type name to its rule set. */
    public static ProfileType of(final String type) {
        if (type == null) return CUSTOM;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "classic", "normal", "survival", "skyblock" -> CLASSIC;
            case "ironman" -> IRONMAN;
            case "stranded" -> STRANDED;
            case "bingo" -> BINGO;
            default -> CUSTOM;
        };
    }

    public boolean trading() {
        return this.trading;
    }

    public boolean auction() {
        return this.auction;
    }

    public boolean bazaar() {
        return this.bazaar;
    }

    public boolean coop() {
        return this.coop;
    }

    public boolean seasonal() {
        return this.seasonal;
    }

    /** Immutable rules for an action boundary. */
    public ProfileRules rules() {
        return new ProfileRules(this.trading, this.auction, this.bazaar, this.coop, this.seasonal);
    }
}
