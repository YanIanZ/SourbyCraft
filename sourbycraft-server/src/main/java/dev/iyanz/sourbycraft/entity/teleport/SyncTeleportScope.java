package dev.iyanz.sourbycraft.entity.teleport;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The entities an admitted synchronous API teleport may move, on the deciding thread, while the
 * call runs. The inherited NMS refusal in {@code Entity#teleport(TeleportTransition)} and
 * {@code ServerPlayer#teleport} stays live for every other caller (vanilla code paths, portals);
 * only members of the current scope pass it (feature patch "owned sync teleport").
 *
 * <p>Membership is identity. A nested teleport started by an event listener opens its own scope
 * and the outer one is restored afterwards, also when the body throws.</p>
 */
public final class SyncTeleportScope {

    private static final ThreadLocal<Set<Object>> CURRENT = new ThreadLocal<>();

    private SyncTeleportScope() {}

    public static boolean run(final Collection<?> members, final BooleanSupplier body) {
        final Set<Object> scope = Collections.newSetFromMap(new IdentityHashMap<>());
        scope.addAll(members);
        final Set<Object> previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return body.getAsBoolean();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * {@link #admits(Object)}, or the entity's root vehicle is admitted: a passenger a teleport-event
     * listener mounted after the snapshot is part of the moving tree too. The caller still checks
     * that it owns the entity.
     */
    public static boolean admits(final Object entity, final java.util.function.Function<Object, Object> rootVehicle) {
        final Set<Object> scope = CURRENT.get();
        return scope != null && (scope.contains(entity) || scope.contains(rootVehicle.apply(entity)));
    }

    public static boolean admits(final Object entity) {
        final Set<Object> scope = CURRENT.get();
        return scope != null && scope.contains(entity);
    }
}
