package dev.iyanz.sourbycraft.awf.multiprofile;

/**
 * Proposed state categories and their scopes; not wired yet into the 26.2 profile engine.
 * These declarations alone do not provide state isolation.
 */
public enum StateCategory {

    // Account-wide.
    GEMS(StateScope.ACCOUNT),
    RANK(StateScope.ACCOUNT),
    COSMETICS(StateScope.ACCOUNT),
    CLIENT_SETTINGS(StateScope.ACCOUNT),

    // Per member.
    INVENTORY(StateScope.MEMBER),
    ARMOR(StateScope.MEMBER),
    OFFHAND(StateScope.MEMBER),
    ENDER_CHEST(StateScope.MEMBER),
    EXPERIENCE(StateScope.MEMBER),
    HEALTH(StateScope.MEMBER),
    FOOD(StateScope.MEMBER),
    POTION_EFFECTS(StateScope.MEMBER),
    LOCATION(StateScope.MEMBER),
    PURSE(StateScope.MEMBER),
    SKILLS(StateScope.MEMBER),
    SLAYERS(StateScope.MEMBER),
    DUNGEONS(StateScope.MEMBER),
    PETS(StateScope.MEMBER),
    WARDROBE(StateScope.MEMBER),
    ACCESSORIES(StateScope.MEMBER),

    // One value per profile, shared by every member.
    BANK(StateScope.PROFILE_SHARED),
    COLLECTIONS(StateScope.PROFILE_SHARED),
    MINIONS(StateScope.PROFILE_SHARED),
    ISLAND_UPGRADES(StateScope.PROFILE_SHARED),
    COMMUNITY_UPGRADES(StateScope.PROFILE_SHARED),
    AUCTIONS(StateScope.PROFILE_SHARED);

    private final StateScope scope;

    StateCategory(final StateScope scope) {
        this.scope = scope;
    }

    public StateScope scope() {
        return this.scope;
    }

    public boolean accountWide() {
        return this.scope == StateScope.ACCOUNT;
    }

    public boolean shared() {
        return this.scope == StateScope.PROFILE_SHARED;
    }

    public boolean personal() {
        return this.scope == StateScope.MEMBER;
    }
}
