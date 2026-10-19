package dev.iyanz.sourbycraft.maxplayers;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Server;

/**
 * Test-only overrides for the two process-wide singletons the maxplayers code reads.
 *
 * <p>{@link SourbyCraftConfig} has no test seam: it reads a hard-coded relative TOML path into a
 * private static {@code loaded} snapshot. Writing that file would touch the module working
 * directory, so the tests swap the in-memory snapshot by reflection and always restore it. A
 * {@code SourbyCraftConfig.overrideForTesting(ConfigSnapshot)} seam would replace this.</p>
 */
final class TestConfigOverride {

    private TestConfigOverride() {}

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void withConfig(final Map<String, Object> values, final ThrowingRunnable action) throws Exception {
        final Field loaded = SourbyCraftConfig.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        final Object previous = loaded.get(null);
        final Class<?> record = loaded.getType();
        final Constructor<?> constructor = record.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        final Method aurora = record.getDeclaredMethod("aurora");
        aurora.setAccessible(true);
        loaded.set(null, constructor.newInstance(new ConfigSnapshot(values), aurora.invoke(previous)));
        try {
            action.run();
        } finally {
            loaded.set(null, previous);
        }
    }

    /** Installs a {@link Server} proxy as {@code Bukkit.server} for the duration of {@code action}. */
    static void withServer(final Server server, final ThrowingRunnable action) throws Exception {
        final Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        final Object previous = field.get(null);
        field.set(null, server);
        try {
            action.run();
        } finally {
            field.set(null, previous);
        }
    }

    static <T> T proxy(final Class<T> type, final Function<java.lang.reflect.Method, Object> onCall,
                       final java.util.function.BiConsumer<java.lang.reflect.Method, Object[]> record) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return type.getSimpleName() + "Proxy";
                    default: break;
                }
                record.accept(method, args);
                final Object result = onCall.apply(method);
                return result != null ? result : zero(method.getReturnType());
            }));
    }

    static Object zero(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        return (short) 0;
    }
}
