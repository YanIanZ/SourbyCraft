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
    // Aurora's four states, from docs/AURORA-UX.md section 1. Every surface colours by
    // state rather than by taste, so the same reading means the same thing in /perf, in a
    // boss bar and in the banner.
    //
    // The previous palette was pastel — #FFB347, #77DD77, #FF6961 — which reads gently at
    // a glance and is the opposite of what a status colour is for. These are saturated
    // enough to separate at a distance on a dark terminal while staying legible.

    /** AURORA — SourbyCraft identity and normal engine state. */
    public static final TextColor AURORA     = TextColor.fromHexString("#22D3EE");
    /** Deep end of the Aurora gradient, for header sweeps. */
    public static final TextColor AURORA_DEEP = TextColor.fromHexString("#6366F1");
    /** HEALTHY — stable, comfortably within budget. */
    public static final TextColor HEALTHY    = TextColor.fromHexString("#4ADE80");
    /** PRESSURE — approaching operational limits. */
    public static final TextColor PRESSURE   = TextColor.fromHexString("#FBBF24");
    /** CRITICAL — a genuine performance or stability concern. */
    public static final TextColor CRITICAL   = TextColor.fromHexString("#F43F5E");

    public static final TextColor PRIMARY    = AURORA;
    public static final TextColor SUCCESS    = HEALTHY;
    public static final TextColor WARNING    = PRESSURE;
    public static final TextColor DANGER     = CRITICAL;
    public static final TextColor INFO       = TextColor.fromHexString("#7DD3FC");
    public static final TextColor ACCENT     = TextColor.fromHexString("#C084FC");
    public static final TextColor HEADER     = AURORA;
    public static final TextColor DIM        = TextColor.fromHexString("#64748B");
    public static final TextColor LABEL      = TextColor.fromHexString("#94A3B8");
    public static final TextColor VALUE      = TextColor.fromHexString("#F1F5F9");

    /**
     * Colour for a measurement against its budget, so every surface agrees on what a number
     * means: healthy below 60%, pressure below 100%, critical at or above it.
     */
    public static TextColor forLoad(final double value, final double budget) {
        if (!Double.isFinite(value) || !Double.isFinite(budget) || budget <= 0.0) {
            return DIM;
        }
        final double ratio = value / budget;
        return ratio < 0.6 ? HEALTHY : ratio < 1.0 ? PRESSURE : CRITICAL;
    }

    /**
     * Interpolates between two colours, for gradient sweeps across a header or a bar.
     *
     * <p>AURORA-UX section 1 restricts gradients to headers, bars and major transitions —
     * per-line gradients are listed under what to avoid, so this is deliberately not
     * something to reach for on ordinary output.
     */
    public static TextColor lerp(final TextColor from, final TextColor to, final double t) {
        final double clamped = t < 0.0 ? 0.0 : t > 1.0 ? 1.0 : t;
        final int r = (int)Math.round(from.red()   + (to.red()   - from.red())   * clamped);
        final int g = (int)Math.round(from.green() + (to.green() - from.green()) * clamped);
        final int b = (int)Math.round(from.blue()  + (to.blue()  - from.blue())  * clamped);
        return TextColor.color(r, g, b);
    }

    private SourbyCraftColors() {}
}
