package dev.iyanz.sourbyclip.cherry;

import dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers;

/**
 * Cherry — SourbyCraft's unified server-side mixin system, merging the two Paper-fork mixin
 * launchers into one that runs natively inside SourbyClip (so mixins "just work": no separate
 * launcher jar, no operator step).
 *
 * <p><b>What Cherry merges.</b>
 * <ul>
 *   <li><b>From LeavesMC (Leavesclip)</b> — the base engine, already vendored in SourbyClip:
 *       SpongePowered Mixin bootstrap via a Fabric-Knot {@code IMixinService}, plugin-mixin
 *       discovery ({@code plugins/*.jar} carrying a manifest + a mixin package), Fabric
 *       access-<i>wideners</i>, MixinExtras, and the {@code leaves-plugin-mixin-condition}
 *       conditional-mixin system. Cherry keeps this as its skeleton.</li>
 *   <li><b>From CraftCanvasMC (Horizon)</b> — the capability Leaves lacked: Forge/Paper-style
 *       access-<i>transformers</i> ({@code .at}). Horizon's {@code TransformerContainer} AT engine
 *       is ported into {@link CherryAccessTransformers} and slotted into the Leaves transforming
 *       classloader. (Horizon itself is a whole separate launcher — its own paperclip, dependency
 *       resolver and Java-agent classloading — which cannot coexist with SourbyClip's launcher, so
 *       merging its <i>architecture</i> wholesale is not possible; its portable, additive
 *       <i>capability</i> is the AT engine, which is what Cherry takes.)</li>
 * </ul>
 *
 * <p><b>Result for a plugin author.</b> Ship one manifest ({@code cherry-plugin.json}, or the
 * legacy {@code leaves-plugin.json}) declaring any of: a {@code mixin} block (Leaves engine:
 * mixins + access-widener) and/or an {@code access-transformers} list (Cherry/Horizon engine).
 * Enable the engine with {@code -Dcherry.enable.mixin=true} (aliases:
 * {@code -Dsourbyclip.enable.mixin}, {@code -Dleavesclip.enable.mixin}).
 */
public final class Cherry {

    public static final String NAME = "Cherry";

    /** System property that turns the Cherry mixin engine on (plus back-compat aliases). */
    public static boolean enabled() {
        return Boolean.getBoolean("cherry.enable.mixin")
            || Boolean.getBoolean("sourbyclip.enable.mixin")
            || Boolean.getBoolean("leavesclip.enable.mixin");
    }

    /**
     * Discover + register plugin access-transformers. Called by the launcher once the mixin
     * environment is up but before the transforming classloader starts loading server classes.
     */
    public static void initAccessTransformers() {
        CherryPluginResolver.resolveAccessTransformers();
    }

    /** @return true if any plugin registered an access-transformer. */
    public static boolean hasAccessTransformers() {
        return !CherryAccessTransformers.INSTANCE.isEmpty();
    }

    /**
     * Apply registered access-transformers to a class about to be defined. No-op (returns the
     * input array) for classes with no AT definitions, so it is cheap to call on every class.
     */
    public static byte[] applyAccessTransformers(byte[] classBytes) {
        return CherryAccessTransformers.INSTANCE.applyToBytes(classBytes);
    }

    private Cherry() {
    }
}
