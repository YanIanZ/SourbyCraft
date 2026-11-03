package dev.iyanz.sourbycraft.awf.multiprofile;

/**
 * Proposed scope of a state category; not wired yet on 26.2.
 *
 * <p>The intended co-op contract is: ACCOUNT state follows the player everywhere,
 * MEMBER state is never merged across members, and PROFILE_SHARED state is one value per profile (and
 * therefore shared by every member of a co-op).</p>
 */
public enum StateScope {
    /** Account-wide: gems, rank, cosmetics, client settings. */
    ACCOUNT,
    /** One value per profile, shared by all its members: bank, collections, minions, upgrades. */
    PROFILE_SHARED,
    /** Per member: inventory, ender chest, skills, purse, location. */
    MEMBER
}
