package dev.iyanz.sourbyclip.cherry.at;

/**
 * Cherry — one parsed access-transformer definition: the {@link AccessChange} to apply, the target
 * kind ({@link ClassData}/{@link FieldData}/{@link MethodData}), and the internal (slash-separated)
 * name of the class it targets.
 *
 * <p>Ported from Horizon's {@code io.canvasmc.horizon.transformer.widener.Definition}.
 *
 * @param operation  the access/finality change to apply to the target
 * @param data       what kind of member is targeted (a whole class, a field, or a method/constructor)
 *                   and the identifying detail for that kind
 * @param nodeTarget the internal (slash-separated, e.g. {@code com/example/Foo}) name of the class
 *                   that owns the target, used as the registry key in {@link CherryAccessTransformers}
 */
public record AtDefinition(AccessChange operation, Data data, String nodeTarget) {

    /**
     * Marker interface for the three kinds of AT target: a class ({@link ClassData}), a field
     * ({@link FieldData}), or a method/constructor ({@link MethodData}).
     */
    public interface Data {
    }

    /**
     * Targets a class declaration itself (e.g. widening a package-private class to public).
     *
     * @param clazzName the dot-separated fully-qualified class name as written in the AT line
     *                   (e.g. {@code com.example.Foo})
     */
    public record ClassData(String clazzName) implements Data {
    }

    /**
     * Targets a single field.
     *
     * @param fieldName the simple (unqualified) field name
     */
    public record FieldData(String fieldName) implements Data {
    }

    /**
     * Targets a single method or constructor.
     *
     * @param methodDescriptor the method name plus its JVM descriptor, in the internal
     *                          {@code name(params)ret} form used to match {@code MethodNode.name} +
     *                          {@code MethodNode.desc} (e.g. {@code doThing(I)V}, or
     *                          {@code <init>(Ljava/lang/String;)V} for a constructor)
     */
    public record MethodData(String methodDescriptor) implements Data {
    }
}
