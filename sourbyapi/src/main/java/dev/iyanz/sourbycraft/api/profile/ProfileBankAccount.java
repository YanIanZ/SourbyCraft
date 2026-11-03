package dev.iyanz.sourbycraft.api.profile;

/**
 * Added in Build 54. The bank accounts of a co-op profile a {@linkplain AuroraProfiles#transfer transfer} moves money
 * between. The purse is deliberately not one (owner decision OD-4): it stays per profile through its REQUIRED state
 * adapter, and the consumer plugin that holds it moves purse money itself and credits or debits the bank with
 * {@link AuroraProfiles#deposit} / {@link AuroraProfiles#withdraw}.
 */
public enum ProfileBankAccount {
    /** The co-op's shared bank. */
    SHARED,
    /** The acting member's personal bank in this co-op. */
    PERSONAL
}
