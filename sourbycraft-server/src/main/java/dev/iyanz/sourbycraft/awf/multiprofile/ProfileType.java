package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.Locale;

/**
 * The rule-set classification of a profile's {@linkplain dev.iyanz.sourbycraft.api.profile.ProfileType
 * policy type}, following Hypixel SkyBlock's published profile types.
 *
 * <p>What is <b>persisted</b> is the API enum {@code dev.iyanz.sourbycraft.api.profile.ProfileType}
 * on {@link AuroraProfile#profileType()}, as a stable numeric tag ({@link #tag(dev.iyanz.sourbycraft.api.profile.ProfileType)}).
 * This server-side enum adds the intended rule set per type and a {@link #CUSTOM} bucket for the free
 * consumer namespace {@link AuroraProfile#type()} when no policy type is known. Build 51 enforces none
 * of these rules; enforcement at the action boundary is planned for Build 55.</p>
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
    /** A consumer-defined namespace with no built-in restrictions; never persisted as a policy type. */
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

    /** Maps a consumer namespace name to its rule set; unknown names are {@link #CUSTOM}. */
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

    /** The rule set of a persisted policy type. */
    public static ProfileType of(final dev.iyanz.sourbycraft.api.profile.ProfileType policy) {
        if (policy == null) return CUSTOM;
        return switch (policy) {
            case CLASSIC -> CLASSIC;
            case IRONMAN -> IRONMAN;
            case STRANDED -> STRANDED;
            case BINGO -> BINGO;
        };
    }

    /**
     * The stable numeric tag a format-2 record stores for a policy type. Tags are fixed per constant
     * and never reused; the API enum's ordinal is not stored.
     */
    public static int tag(final dev.iyanz.sourbycraft.api.profile.ProfileType policy) {
        return switch (policy) {
            case CLASSIC -> 1;
            case IRONMAN -> 2;
            case STRANDED -> 3;
            case BINGO -> 4;
        };
    }

    /** The policy type for a stored tag; an unknown tag is a corrupt or newer record and is refused. */
    public static dev.iyanz.sourbycraft.api.profile.ProfileType fromTag(final int tag, final String where)
        throws IOException {
        return switch (tag) {
            case 1 -> dev.iyanz.sourbycraft.api.profile.ProfileType.CLASSIC;
            case 2 -> dev.iyanz.sourbycraft.api.profile.ProfileType.IRONMAN;
            case 3 -> dev.iyanz.sourbycraft.api.profile.ProfileType.STRANDED;
            case 4 -> dev.iyanz.sourbycraft.api.profile.ProfileType.BINGO;
            default -> throw new IOException("profile type tag " + tag + " in " + where + " is not known to this build");
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

    /** Immutable rules for an action boundary (not enforced in Build 51). */
    public ProfileRules rules() {
        return new ProfileRules(this.trading, this.auction, this.bazaar, this.coop, this.seasonal);
    }
}
