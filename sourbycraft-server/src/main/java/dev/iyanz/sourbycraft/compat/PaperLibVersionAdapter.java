package dev.iyanz.sourbycraft.compat;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Lets PaperLib, shaded into plugins such as EssentialsX, recognise a 26.x server.
 *
 * <p>PaperLib 1.0.x ({@code io.papermc.lib}, archived upstream, relocated by each plugin) picks
 * its chunk and teleport implementations from {@code Bukkit.getVersion()} parsed with
 * {@link #PAPERLIB_VERSION_REGEX}. That pattern only accepts a one-digit major ({@code 1.x}), so
 * {@code "(MC: 26.2)"} never matches, the parsed version stays 0, and PaperLib keeps its
 * synchronous fallbacks even though it detected Paper: {@code getChunkAtAsync} becomes
 * {@code World#getChunkAt} on the calling thread and {@code teleportAsync} becomes
 * {@code Entity#teleport}. Stock Paper 26.x has the same parse result; there it only costs a
 * synchronous load on the one main thread. On a region-threaded server a region thread may not
 * load or read a chunk it does not own, so the region-ownership guard refuses it ("Async chunk
 * retrieval") and the plugin's teleport fails (EssentialsX warp, spawn, home, tpa, new-player
 * spawn).</p>
 *
 * <p>The adaptation is the narrowest change that keeps the guard intact: in a class named
 * {@code .../environments/Environment} whose constructor loads exactly PaperLib's regex and then
 * calls {@code Bukkit.getVersion()}, that one call is replaced by the constant
 * {@link #PAPERLIB_LEGACY_VERSION}. PaperLib then selects {@code World#getChunkAtAsync} and
 * {@code Entity#teleportAsync}, which schedule on the owning region. Nothing else in the plugin,
 * and no other version reader, sees a different value; {@code Bukkit.getVersion()} itself is
 * unchanged. 1.21.11 is the last 1.x release; the 26.x API is a superset of it for every feature
 * PaperLib gates (its highest gate is 1.15.2, and its 1.15.2 reflective probe still falls back
 * cleanly when a method is gone).</p>
 *
 * <p>Applied while a plugin class is defined (legacy {@code PluginClassLoader} through
 * {@code CraftMagicNumbers#processClass}, Paper plugins through
 * {@code PaperClassloaderBytecodeModifier}; feature patch "PaperLib version parse"). Always on;
 * {@code -Dsourbycraft.paperlib.adapt=false} turns it off (RESTART_REQUIRED, JVM flag).</p>
 */
public final class PaperLibVersionAdapter {

    /** PaperLib 1.0.x {@code Environment} version pattern, as the class-file constant. */
    public static final String PAPERLIB_VERSION_REGEX = "\\(MC: (\\d)\\.(\\d+)\\.?(\\d+?)?(?: Pre-Release )?(\\d)?\\)";

    /** What PaperLib's parse is given instead of {@code Bukkit.getVersion()}. */
    public static final String PAPERLIB_LEGACY_VERSION = "(MC: 1.21.11)";

    static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("sourbycraft.paperlib.adapt"));

    private static final byte[] MARKER = PAPERLIB_VERSION_REGEX.getBytes(StandardCharsets.UTF_8);
    private static final String ENVIRONMENT_SUFFIX = "/environments/Environment";

    private PaperLibVersionAdapter() {}

    /** Returns {@code bytecode} itself unless it is PaperLib's Environment, in which case the adapted class. */
    public static byte[] adapt(final String pluginName, final byte[] bytecode) {
        return adapt(pluginName, bytecode, ENABLED);
    }

    static byte[] adapt(final String pluginName, final byte[] bytecode, final boolean enabled) {
        if (!enabled || bytecode == null || indexOf(bytecode, MARKER) < 0) {
            return bytecode;
        }
        try {
            final ClassReader reader = new ClassReader(bytecode);
            if (!reader.getClassName().endsWith(ENVIRONMENT_SUFFIX)) {
                return bytecode;
            }
            final ClassWriter writer = new ClassWriter(reader, 0);
            final Rewriter rewriter = new Rewriter(writer);
            reader.accept(rewriter, 0);
            if (rewriter.replaced != 1) {
                return bytecode;
            }
            SourbyLogger.info("PaperLib in " + (pluginName == null ? "a plugin" : pluginName) + " (" + reader.getClassName().replace('/', '.')
                + "): version check adapted for 26.x, so it uses World#getChunkAtAsync and Entity#teleportAsync instead of its synchronous fallbacks");
            return writer.toByteArray();
        } catch (final RuntimeException malformed) {
            return bytecode;
        }
    }

    private static int indexOf(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final class Rewriter extends ClassVisitor {

        int replaced;

        Rewriter(final ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
            final MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!"<init>".equals(name)) {
                return next;
            }
            return new MethodVisitor(Opcodes.ASM9, next) {
                private boolean sawPattern;

                @Override
                public void visitLdcInsn(final Object value) {
                    if (PAPERLIB_VERSION_REGEX.equals(value)) {
                        this.sawPattern = true;
                    }
                    super.visitLdcInsn(value);
                }

                @Override
                public void visitMethodInsn(final int opcode, final String owner, final String methodName, final String methodDescriptor, final boolean isInterface) {
                    if (this.sawPattern && opcode == Opcodes.INVOKESTATIC && "org/bukkit/Bukkit".equals(owner)
                        && "getVersion".equals(methodName) && "()Ljava/lang/String;".equals(methodDescriptor)) {
                        this.sawPattern = false;
                        Rewriter.this.replaced++;
                        super.visitLdcInsn(PAPERLIB_LEGACY_VERSION); // same stack effect: () -> String
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                }
            };
        }
    }
}
