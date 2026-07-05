package dev.iyanz.sourbycraft.core;

import org.bukkit.plugin.Plugin;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SourbyCraft MT1 — central module lifecycle registry.
 *
 * <p>Modules are added in registration order via {@link #add(String, EnableFn)}
 * (functional convenience) or {@link #add(SourbyModule)}. {@link #enableAll(Plugin)}
 * iterates in registration order; each module is wrapped in try/catch(Throwable) so
 * a failure never aborts onEnable. A single summary INFO line is emitted after all
 * attempts. {@link #disableAll()} iterates in reverse registration order.
 */
public final class ModuleRegistry {

    /** Functional alias for {@link SourbyModule#enable(Plugin)} — allows lambda enrollment. */
    @FunctionalInterface
    public interface EnableFn {
        void enable(Plugin plugin) throws Exception;
    }

    private static final List<SourbyModule> MODULES = new ArrayList<>();
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SourbyCraft");

    /** Convenience: enroll a named enable function as a module. */
    public static void add(String name, EnableFn fn) {
        add(new SourbyModule() {
            @Override public String name() { return name; }
            @Override public void enable(Plugin plugin) throws Exception { fn.enable(plugin); }
        });
    }

    /** Enroll a full {@link SourbyModule} instance. */
    public static void add(SourbyModule module) {
        MODULES.add(module);
    }

    /**
     * Enable all enrolled modules in registration order. Each module is wrapped in
     * try/catch(Throwable); failures log {@code Failed to enable module <name> — continuing}.
     * One summary INFO line is emitted after all attempts.
     */
    /**
     * Clear enrolled modules. Called at the top of {@code SWPlugin.onEnable} so a
     * same-classloader reload (third-party reload plugins) cannot double-enroll —
     * two schedulers/listeners per module would result otherwise.
     */
    public static void clear() {
        MODULES.clear();
    }

    public static void enableAll(Plugin plugin) {
        int enabled = 0, failed = 0;
        StringBuilder sb = new StringBuilder("[SourbyCraft] modules:");
        for (SourbyModule m : MODULES) {
            try {
                m.enable(plugin);
                sb.append(' ').append(m.name()).append('✓');
                enabled++;
            } catch (Throwable t) {
                LOG.error("Failed to enable module {} — continuing", m.name(), t);
                sb.append(' ').append(m.name()).append('✗');
                failed++;
            }
        }
        sb.append(" (").append(enabled).append(" enabled, ").append(failed).append(" failed)");
        LOG.info(sb.toString());
    }

    /**
     * Disable all enrolled modules in reverse registration order. Each module is
     * wrapped in try/catch(Throwable) so failures never interrupt the shutdown sequence.
     */
    public static void disableAll() {
        List<SourbyModule> reversed = new ArrayList<>(MODULES);
        Collections.reverse(reversed);
        for (SourbyModule m : reversed) {
            try {
                m.disable();
            } catch (Throwable t) {
                LOG.error("Failed to disable module {} — continuing", m.name(), t);
            }
        }
    }

    private ModuleRegistry() {}
}
