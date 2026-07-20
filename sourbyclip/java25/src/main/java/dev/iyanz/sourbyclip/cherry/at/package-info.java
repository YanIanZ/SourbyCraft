/**
 * Cherry's access-transformer (AT) engine — the capability grafted in from CraftCanvasMC/Horizon's
 * {@code TransformerContainer}, giving SourbyCraft plugins Forge/Paper-style access-transformers
 * ({@code .at} files) alongside the Fabric access-wideners the Leaves mixin loader already had.
 *
 * <p>Pipeline through this package, in order:
 * <ol>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers#register(String, java.io.BufferedReader)}
 *       parses an {@code .at} file line-by-line into {@link dev.iyanz.sourbyclip.cherry.at.AtDefinition}s
 *       (throwing {@link dev.iyanz.sourbyclip.cherry.at.CompileError} on a malformed line), each
 *       carrying an {@link dev.iyanz.sourbyclip.cherry.at.AccessChange} to apply.</li>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers#lock()} resolves conflicting
 *       definitions on the same target (widest access wins, finality is always cleared) and freezes
 *       the registry before class loading begins.</li>
 *   <li>{@link dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers#applyToBytes(byte[])} is
 *       invoked once per loaded class by the host's transforming classloader, rewriting matched
 *       classes/fields/methods with ASM and leaving everything else byte-for-byte unchanged.</li>
 * </ol>
 *
 * <p>The AT line grammar and ASM access-flag arithmetic are preserved verbatim from Horizon, so an
 * {@code .at} file authored for Horizon parses and applies identically under Cherry.
 */
package dev.iyanz.sourbyclip.cherry.at;
