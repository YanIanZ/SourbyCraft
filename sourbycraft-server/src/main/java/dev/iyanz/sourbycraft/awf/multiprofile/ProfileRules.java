package dev.iyanz.sourbycraft.awf.multiprofile;


/**
 * Proposed action permissions for a profile type; not wired yet on 26.2.
 * Action-boundary enforcement (trade, auction, bazaar, co-op invite) is intended, not implemented
 * by this value type.
 *
 * @param trading player-to-player trading
 * @param auction auction-house access
 * @param bazaar bazaar access
 * @param coop whether more members may join
 * @param seasonal whether the profile is archived at the end of its season instead of kept forever
 */
public record ProfileRules(boolean trading, boolean auction, boolean bazaar, boolean coop, boolean seasonal) {

    /** A rule set that refuses every restricted action. */
    public static final ProfileRules ALL_OFF = new ProfileRules(false, false, false, false, false);

    /** Whether an action is allowed by this rule set. */
    public boolean allows(final Action action) {
        return switch (action) {
            case TRADE -> this.trading;
            case AUCTION -> this.auction;
            case BAZAAR -> this.bazaar;
            case INVITE_MEMBER -> this.coop;
        };
    }

    /** Actions a profile rule set can refuse. */
    public enum Action {
        TRADE,
        AUCTION,
        BAZAAR,
        INVITE_MEMBER
    }
}
