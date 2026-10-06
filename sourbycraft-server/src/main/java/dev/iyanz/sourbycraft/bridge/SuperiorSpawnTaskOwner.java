package dev.iyanz.sourbycraft.bridge;

import dev.iyanz.sourbycraft.execution.region.RegionAnchor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Owner extraction for the audited SuperiorSkyblock2 2026.3 spawn-biome initialization callback.
 * This is an adapter, not a port of SuperiorSkyblock code. See SUPERIORSKYBLOCK-BRIDGE.md for
 * the artifact fingerprint and the bytecode audit. Other callbacks are not inferred by reflection.
 */
final class SuperiorSpawnTaskOwner {

    private static final String OWNER = "com.bgsoftware.superiorskyblock.island.SpawnIsland";
    private static final String DIMENSION = "com.bgsoftware.superiorskyblock.api.world.Dimension";
    private static final String POSITION = "com.bgsoftware.superiorskyblock.core.SWorldPosition";
    private static final String AUDITED_SHA256 =
        "fdfbff8e58fe4db81640c0e03edc49eee94dd8726cbc7661da88a70f604f69ed";
    private static final String POSITION_SHA256 =
        "d2c86dee7049f0b1e937ee08dceb93531a7e8bfdd7a46cd6f3736e31908db126";
    private static final int MAX_CLASS_BYTES = 1024 * 1024;

    private record Accessor(Field captured, Method center, String refusal) {
        static Accessor refuse(final String reason) {
            return new Accessor(null, null, reason);
        }

        RegionAnchor resolve(final Object callback) {
            if (this.refusal != null) throw new IllegalArgumentException(this.refusal);
            try {
                final Object island = this.captured.get(callback);
                if (island == null || island.getClass() != this.center.getDeclaringClass()) {
                    throw new IllegalArgumentException("SuperiorSkyblock2 spawn callback has an unaudited island subclass");
                }
                final Location location = (Location)this.center.invoke(island, new Object[] {null});
                if (location == null || location.getWorld() == null) {
                    throw new IllegalArgumentException("SuperiorSkyblock2 spawn callback has no world location");
                }
                return new RegionAnchor(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
            } catch (final IllegalAccessException | InvocationTargetException failed) {
                throw new IllegalArgumentException("Cannot resolve SuperiorSkyblock2 spawn callback owner", failed);
            }
        }
    }

    // ClassValue does not retain plugin classloaders after unload. Access failures are cached too.
    private static final ClassValue<Accessor> ACCESSORS = new ClassValue<>() {
        @Override
        protected Accessor computeValue(final Class<?> callbackClass) {
            final Field[] captures = callbackClass.getDeclaredFields();
            if (!callbackClass.isHidden() || !callbackClass.isSynthetic() || captures.length != 1) {
                return Accessor.refuse("Unrecognized SuperiorSkyblock2 spawn callback shape; owner routing refused");
            }
            final Field captured = captures[0];
            final Class<?> owner = captured.getType();
            if (!owner.getName().equals(OWNER) || callbackClass.getNestHost() != owner
                || owner.getClassLoader() != callbackClass.getClassLoader()
                || Modifier.isStatic(captured.getModifiers()) || !Modifier.isFinal(captured.getModifiers())) {
                return Accessor.refuse("Unrecognized SuperiorSkyblock2 spawn callback capture; owner routing refused");
            }
            try {
                final Class<?> position = Class.forName(POSITION, false, owner.getClassLoader());
                if (!hasFingerprint(owner, AUDITED_SHA256) || !hasFingerprint(position, POSITION_SHA256)) {
                    return Accessor.refuse("SuperiorSkyblock2 SpawnIsland/SWorldPosition differ from the audited 2026.3 classes;"
                        + " spawn callback needs an owner-aware plugin update or adapter review");
                }
                final Class<?> dimension = Class.forName(DIMENSION, false, owner.getClassLoader());
                final Method center = owner.getMethod("getCenter", dimension);
                if (center.getReturnType() != Location.class || !captured.trySetAccessible()) {
                    return Accessor.refuse("Cannot access audited SuperiorSkyblock2 spawn callback metadata");
                }
                return new Accessor(captured, center, null);
            } catch (final IOException | ReflectiveOperationException | NoSuchAlgorithmException | RuntimeException failed) {
                return Accessor.refuse("Cannot inspect SuperiorSkyblock2 spawn callback: " + failed);
            }
        }
    };

    private SuperiorSpawnTaskOwner() {}

    private static boolean hasFingerprint(final Class<?> type, final String expected)
        throws IOException, NoSuchAlgorithmException {
        try (InputStream bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (bytes == null) return false;
            final byte[] classBytes = bytes.readNBytes(MAX_CLASS_BYTES + 1);
            return classBytes.length <= MAX_CLASS_BYTES && expected.equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(classBytes)));
        }
    }

    /** Returns null for unrelated tasks; a recognized but unsupported callback is refused. */
    static RegionAnchor resolve(final Plugin plugin, final Object callback) {
        if (callback == null || !"SuperiorSkyblock2".equals(plugin.getName())
            || !(callback instanceof Runnable)
            || !callback.getClass().getName().startsWith(OWNER + "$$Lambda")) return null;
        // SpawnIsland also has a static settings-listener Runnable with no captured island.
        // Only its single-capture instance Runnable is the audited constructor callback.
        final Field[] captures = callback.getClass().getDeclaredFields();
        if (captures.length != 1 || !captures[0].getType().getName().equals(OWNER)) return null;
        final var meta = plugin.getPluginMeta();
        if (meta == null || !"2026.3".equals(meta.getVersion())
            || callback.getClass().getClassLoader() != plugin.getClass().getClassLoader()) {
            throw new IllegalArgumentException("Unsupported SuperiorSkyblock2 spawn callback version/classloader;"
                + " owner routing refused");
        }
        return ACCESSORS.get(callback.getClass()).resolve(callback);
    }
}
