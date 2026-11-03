package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 54. The rules in force for one profile, as {@link ProfileTypeRegistry#rules(UUID)} returns them.
 *
 * @param profileId the profile
 * @param typeId the profile's type id ({@link ProfileTypeRegistry#typeId})
 * @param definition the type's definition in force, or {@code null} when this server knows no definition for
 *     {@code typeId}
 * @param unknownAllows what an unknown type answers for every action: {@code false} under the server's default
 *     {@code types.unknown-type-policy: deny}
 */
@NullMarked
public record ProfileRuleSet(UUID profileId, String typeId, @Nullable ProfileTypeDefinition definition,
                             boolean unknownAllows) {

    public ProfileRuleSet {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(typeId, "typeId");
    }

    /** Whether the server knows a definition for the profile's type. */
    public boolean known() {
        return this.definition != null;
    }

    /** Whether the action is allowed ({@link ProfileTypeDefinition#allows}, or {@link #unknownAllows()}). */
    public boolean allows(final String action) {
        return this.definition != null ? this.definition.allows(action) : this.unknownAllows;
    }
}
