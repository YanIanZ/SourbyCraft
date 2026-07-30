package dev.iyanz.sourbycraft;

import net.kyori.adventure.text.format.TextColor;

/**
 * The single hex-colour palette every SourbyCraft-branded surface renders with.
 *
 * <p>Commands ({@code /tps}, {@code /sys}, {@code /ver}, ...), the HUD boss bars, the startup
 * banner + GC advisor, and the varied join/leave/kick messages all pull their {@link TextColor}s
 * from here instead of hard-coding hex strings, so a palette change is one file — and every panel
 * keeps reading as one coherent SourbyCraft look.
 *
 * <p>Semantic roles, not literal shades: {@link #SUCCESS}/{@link #DANGER} mark good/bad readouts,
 * {@link #LABEL}/{@link #VALUE} distinguish a field name from its value, {@link #DIM} is
 * de-emphasized chrome (dividers, "unavailable" text), and {@link #HEADER} currently mirrors
 * {@link #PRIMARY}.
 */
public final class SourbyCraftColors {
    public static final TextColor PRIMARY    = TextColor.fromHexString("#FFB347");
    public static final TextColor SUCCESS    = TextColor.fromHexString("#77DD77");
    public static final TextColor WARNING    = TextColor.fromHexString("#FF6961");
    public static final TextColor INFO       = TextColor.fromHexString("#AEC6CF");
    public static final TextColor ACCENT     = TextColor.fromHexString("#CBA6F7");
    public static final TextColor DANGER     = TextColor.fromHexString("#FF5555");
    public static final TextColor HEADER     = TextColor.fromHexString("#FFB347");
    public static final TextColor DIM        = TextColor.fromHexString("#808080");
    public static final TextColor LABEL      = TextColor.fromHexString("#FFD700");
    public static final TextColor VALUE      = TextColor.fromHexString("#FFFFFF");

    private SourbyCraftColors() {}
}
