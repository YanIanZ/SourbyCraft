package dev.yanianz.intave.block.fluid;

import org.bukkit.Material;

/** Retained internal name; this private native build supports only the owning 26.2 server. */
final class v18b2FluidResolver implements FluidResolver {
    private final v26FluidResolver nativeResolver = new v26FluidResolver();
    @Override public Fluid liquidFrom(Material type, int variantIndex) {
        return nativeResolver.liquidFrom(type, variantIndex);
    }
}
