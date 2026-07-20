package dev.iyanz.sourbyclip.cherry.at;

import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cherry — the access-transformer (AT) engine. This is the capability grafted in from
 * CraftCanvasMC/Horizon (Horizon's {@code TransformerContainer}); the Leaves mixin loader Cherry is
 * built on only supports Fabric access-<i>wideners</i>, not Forge/Paper-style access-<i>transformers</i>.
 * Cherry gives SourbyCraft plugins BOTH.
 *
 * <p>Differences from Horizon's original, all to make it self-contained inside SourbyClip's
 * Leaves-based launcher (which is a plain {@code URLClassLoader}, not Horizon's agent/service
 * architecture):
 * <ul>
 *   <li>{@code it.unimi.dsi.fastutil} maps → JDK {@link HashMap}/{@link HashSet} (no fastutil on
 *       the clip classpath).</li>
 *   <li>{@code lock()}'s mixin-service target-validation pass is dropped — Cherry validates lazily
 *       at apply time instead (missing field/method targets are logged and skipped, never fatal),
 *       because SourbyClip's Leaves {@code MixinServiceKnot} is not the Horizon
 *       {@code BootstrapMixinService} the original queried.</li>
 *   <li>Adds {@link #applyToBytes(byte[])} so the Leaves {@code MixinURLClassLoader} can run AT as
 *       one more step in its transform chain (mixin → AT → access-widener → defineClass).</li>
 * </ul>
 * The AT line grammar + the ASM access-flag maths are preserved verbatim, so an {@code .at} file
 * authored for Horizon parses and applies identically under Cherry.
 *
 * <p><b>Thread-safety.</b> {@link #register(String, BufferedReader)} and {@link #lock()} are
 * expected to run once, sequentially, during the single-threaded plugin-discovery phase, before any
 * class is loaded — both are {@code synchronized} against a private staging map that no other
 * method ever touches. {@link #applyToBytes(byte[])} (and {@link #isEmpty()}/{@link #registeredCount()}/
 * {@link #targetClassCount()}), by contrast, are called from the transforming classloader's
 * {@code findClass}, which can run concurrently across multiple class-loading threads. To make that
 * safe without any locking on the hot path, {@link #lock()} builds one fully immutable snapshot of
 * the resolved registry and publishes it through a single {@code volatile} reference; every reader
 * takes exactly one volatile read of that reference and then only ever sees a finished, unmodifiable
 * {@link Map} of unmodifiable {@link Set}s — there is no shared mutable state on the read path, and
 * no way for a reader to observe a partially-built registry.
 */
public final class CherryAccessTransformers {

    /**
     * The single, process-wide AT registry. Cherry has no per-plugin or per-classloader isolation:
     * every {@code access-transformers} declaration discovered by
     * {@link dev.iyanz.sourbyclip.cherry.CherryPluginResolver} is registered into this one instance,
     * which is then {@linkplain #lock() locked} once before class loading begins.
     */
    public static final CherryAccessTransformers INSTANCE = new CherryAccessTransformers();

    private static final Logger LOGGER = new SimpleLogger("Cherry/AT");

    // classes: <modifier> <fqcn>
    // fields:  <modifier> <fqcn> <field>
    // methods: <modifier> <fqcn> <method>(<params>)<ret>
    private static final Pattern CLASS_REGEX =
        Pattern.compile("^\\s*(public|protected|private|default)([+-]f)?\\s+([A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*)\\s*$");
    private static final Pattern FIELD_REGEX =
        Pattern.compile("^\\s*(public|protected|private|default)([+-]f)?\\s+([A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$");
    private static final Pattern CONSTRUCTOR_REGEX =
        Pattern.compile("^\\s*(public|protected|private|default)([+-]f)?\\s+([A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*)\\s+<init>\\s*\\(([^)]*)\\)\\s*V\\s*$");
    private static final Pattern METHOD_REGEX =
        Pattern.compile("^\\s*(public|protected|private|default)([+-]f)?\\s+([A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*)\\s+([A-Za-z_][A-Za-z0-9_<>]*)\\s*\\(([^)]*)\\)\\s*(\\[*(?:[VIJZBCSFD]|L[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*;))\\s*$");
    private static final Pattern MODIFIER_PREFIX =
        Pattern.compile("^\\s*(public|protected|private|default)([+-]f)?\\b");

    private static final int VIS_PUBLIC = 3;
    private static final int VIS_PROTECTED = 2;
    private static final int VIS_PRIVATE = 1;

    /** internal class name (a/b/C) -> its AT definitions, mutated only under this instance's monitor, pre-lock. */
    private final Map<String, Set<AtDefinition>> staging = new HashMap<>();

    /** Immutable, fully-resolved snapshot published by {@link #lock()}; empty until then. */
    private volatile Map<String, Set<AtDefinition>> committed = Map.of();

    private volatile boolean locked = false;
    private final AtomicInteger registeredCounter = new AtomicInteger();

    private CherryAccessTransformers() {
    }

    /** @return true if no access-transformer definitions have been registered (yet). Safe from any thread. */
    public boolean isEmpty() {
        return registeredCounter.get() == 0;
    }

    /** @return the total number of AT definitions registered across all target classes so far. Safe from any thread. */
    public int registeredCount() {
        return registeredCounter.get();
    }

    /** @return the number of distinct target classes with at least one AT definition. Safe from any thread. */
    public synchronized int targetClassCount() {
        return locked ? committed.size() : staging.size();
    }

    /** @return true once {@link #lock()} has run and the registry is a frozen, read-only snapshot. */
    public boolean isLocked() {
        return locked;
    }

    /**
     * Parse one access-transformer file (one definition per line, {@code #} comments allowed) and
     * add its definitions to the registry. Never throws for a malformed line - each line is
     * compiled independently, and a line that cannot be parsed (or throws unexpectedly while being
     * parsed) is logged and skipped, leaving every other line in this file, and every other file,
     * completely unaffected.
     *
     * @param sourceName human-readable name for logging (e.g. {@code MyPlugin:widener.at})
     * @param reader     the AT file contents
     */
    public synchronized void register(String sourceName, BufferedReader reader) {
        if (locked) {
            throw new IllegalStateException("Cherry AT registry is locked");
        }
        String line;
        int idx = 0;
        try {
            while ((line = reader.readLine()) != null) {
                idx++;
                int pos = line.indexOf('#');
                String trimmed = (pos == -1 ? line : line.substring(0, pos)).trim();
                if (trimmed.isBlank()) continue;
                try {
                    AtDefinition compiled = tryCompile(trimmed);
                    if (compiled != null) {
                        addDefinition(compiled.nodeTarget(), compiled);
                    } else {
                        LOGGER.warn("Cherry: could not parse AT line ({}) in {}: \"{}\"", idx, sourceName, line);
                    }
                } catch (CompileError e) {
                    LOGGER.error("Cherry: invalid AT line ({}) in {}: {}", idx, sourceName, e.getMessage());
                } catch (RuntimeException e) {
                    // Belt-and-braces: an AT line must never be able to abort loading every other
                    // declaration in this file (or any other plugin's), no matter what it contains.
                    LOGGER.error("Cherry: unexpected error parsing AT line ({}) in {}: \"{}\" ({}: {})",
                        idx, sourceName, line, e.getClass().getSimpleName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Cherry: failed reading AT source " + sourceName, e);
        }
    }

    private void addDefinition(String internalClassName, AtDefinition def) {
        staging.computeIfAbsent(internalClassName, k -> new HashSet<>()).add(def);
        registeredCounter.incrementAndGet();
    }

    /**
     * Resolve intra-class conflicts (keep the widest access, drop finality), publish one immutable
     * snapshot of the result, and freeze the registry. Idempotent - a second call is a no-op.
     */
    public synchronized void lock() {
        if (locked) return;

        Map<String, Set<AtDefinition>> resolved = new HashMap<>();
        for (Map.Entry<String, Set<AtDefinition>> e : staging.entrySet()) {
            Set<AtDefinition> defs = e.getValue();
            if (defs == null || defs.isEmpty()) continue;

            Map<String, AtDefinition> best = new HashMap<>();
            for (AtDefinition def : defs) {
                final String key = switch (def.data()) {
                    case AtDefinition.ClassData ignored -> "CLASS";
                    case AtDefinition.FieldData f -> "F:" + f.fieldName();
                    case AtDefinition.MethodData m -> "M:" + m.methodDescriptor();
                    default -> null;
                };
                if (key == null) continue;
                AtDefinition existing = best.get(key);
                if (existing == null || visibilityRank(def.operation().access()) > visibilityRank(existing.operation().access())) {
                    best.put(key, def);
                }
            }

            Set<AtDefinition> cleaned = new HashSet<>(best.size());
            for (AtDefinition d : best.values()) {
                // Cannot know the original finality, so always clear it (matches Horizon).
                AccessChange op = AccessChange.builder()
                    .access(d.operation().access())
                    .finality(AccessChange.Finality.REMOVE)
                    .build();
                cleaned.add(new AtDefinition(op, d.data(), d.nodeTarget()));
            }
            if (!cleaned.isEmpty()) {
                resolved.put(e.getKey(), Set.copyOf(cleaned));
            }
        }

        // Publish the fully-built, immutable snapshot in one volatile write, then flip the flag.
        // Any thread that later calls applyToBytes/targetClassCount sees either the old (empty)
        // state or this complete one - never a partially populated map.
        this.committed = Map.copyOf(resolved);
        this.locked = true;
        LOGGER.info("Cherry: locked {} access-transformer definition(s) across {} target class(es)",
            registeredCounter.get(), committed.size());
    }

    /**
     * @return the transformed bytes if this class has AT definitions, else the input unchanged.
     * Safe to call from any thread, concurrently, including before {@link #lock()} has run (in
     * which case it is always a no-op, since {@link #committed} starts out empty).
     */
    public byte[] applyToBytes(byte[] classBytes) {
        Map<String, Set<AtDefinition>> snapshot = committed;
        if (snapshot.isEmpty()) {
            return classBytes;
        }
        ClassReader reader = new ClassReader(classBytes);
        String internalName = reader.getClassName();
        Set<AtDefinition> mods = snapshot.get(internalName);
        if (mods == null) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        transformNode(node, mods);
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private void transformNode(ClassNode toTransform, Set<AtDefinition> mods) {
        for (AtDefinition def : mods) {
            switch (def.data()) {
                case AtDefinition.ClassData ignored -> {
                    toTransform.access = def.operation().apply(toTransform.access);
                    LOGGER.debug("Cherry: access-transformed class {}", toTransform.name);
                }
                case AtDefinition.FieldData fdata -> {
                    FieldNode target = find(toTransform.fields, f -> f.name.equals(fdata.fieldName()));
                    if (target == null) {
                        LOGGER.warn("Cherry: AT target field '{}' not found in {}", fdata.fieldName(), toTransform.name);
                    } else {
                        target.access = def.operation().apply(target.access);
                        LOGGER.debug("Cherry: access-transformed field {}.{}", toTransform.name, target.name);
                    }
                }
                case AtDefinition.MethodData mdata -> {
                    String descriptor = mdata.methodDescriptor();
                    int idx = descriptor.indexOf('(');
                    String mName = descriptor.substring(0, idx);
                    String mDesc = descriptor.substring(idx);
                    MethodNode target = find(toTransform.methods, m -> mName.equals(m.name) && mDesc.equals(m.desc));
                    if (target == null) {
                        LOGGER.warn("Cherry: AT target method '{}' not found in {}", descriptor, toTransform.name);
                    } else {
                        target.access = def.operation().apply(target.access);
                        LOGGER.debug("Cherry: access-transformed method {}.{}", toTransform.name, target.name);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static <T> T find(java.util.List<T> list, java.util.function.Predicate<T> pred) {
        for (Iterator<T> it = list.iterator(); it.hasNext(); ) {
            T t = it.next();
            if (pred.test(t)) return t;
        }
        return null;
    }

    private static int visibilityRank(AccessChange.Access access) {
        return switch (access) {
            case PUBLIC, DEFAULT -> VIS_PUBLIC; // widen defaults to public on conflict (Horizon behaviour)
            case PROTECTED -> VIS_PROTECTED;
            case PRIVATE -> VIS_PRIVATE;
        };
    }

    private static AccessChange parseOperation(String token) {
        boolean touchesFinal = token.endsWith("-f") || token.endsWith("+f");
        int len = token.length();
        String op = touchesFinal ? token.substring(0, len - 2) : token;
        String f = touchesFinal ? token.substring(len - 2, len) : "none";
        return AccessChange.builder()
            .access(AccessChange.Access.valueOf(op.toUpperCase()))
            .finality(f.equals("none") ? AccessChange.Finality.NONE
                : f.equals("-f") ? AccessChange.Finality.REMOVE : AccessChange.Finality.ADD)
            .build();
    }

    private AtDefinition tryCompile(String trimmed) throws CompileError {
        if (!MODIFIER_PREFIX.matcher(trimmed).find()) {
            throw new CompileError("invalid access modifier in '" + trimmed + "'");
        }
        Matcher m;

        m = CLASS_REGEX.matcher(trimmed);
        if (m.matches()) {
            String clazz = m.group(3);
            return new AtDefinition(parseOperation(m.group(1) + n(m.group(2))),
                new AtDefinition.ClassData(clazz), clazz.replace('.', '/'));
        }
        m = FIELD_REGEX.matcher(trimmed);
        if (m.matches()) {
            String clazz = m.group(3);
            return new AtDefinition(parseOperation(m.group(1) + n(m.group(2))),
                new AtDefinition.FieldData(m.group(4)), clazz.replace('.', '/'));
        }
        m = CONSTRUCTOR_REGEX.matcher(trimmed);
        if (m.matches()) {
            String clazz = m.group(3);
            return new AtDefinition(parseOperation(m.group(1) + n(m.group(2))),
                new AtDefinition.MethodData("<init>(" + m.group(4) + ")V"), clazz.replace('.', '/'));
        }
        m = METHOD_REGEX.matcher(trimmed);
        if (m.matches()) {
            String clazz = m.group(3);
            return new AtDefinition(parseOperation(m.group(1) + n(m.group(2))),
                new AtDefinition.MethodData(m.group(4) + "(" + m.group(5) + ")" + m.group(6)),
                clazz.replace('.', '/'));
        }
        return null;
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
