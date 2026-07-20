package dev.iyanz.sourbyclip.cherry.at;

/**
 * Cherry — one parsed access-transformer definition: the {@link AccessChange} to apply, the target
 * kind ({@link ClassData}/{@link FieldData}/{@link MethodData}), and the internal (slash-separated)
 * name of the class it targets.
 *
 * <p>Ported from Horizon's {@code io.canvasmc.horizon.transformer.widener.Definition}.
 */
public record AtDefinition(AccessChange operation, Data data, String nodeTarget) {

    public interface Data {
    }

    public record ClassData(String clazzName) implements Data {
    }

    public record FieldData(String fieldName) implements Data {
    }

    public record MethodData(String methodDescriptor) implements Data {
    }
}
