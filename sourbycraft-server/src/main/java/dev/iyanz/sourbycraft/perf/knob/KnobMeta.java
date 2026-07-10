package dev.iyanz.sourbycraft.perf.knob;

import java.util.List;

/**
 * Declared metadata for one config key. Immutable; built via the static
 * factories + withers so declarations in {@code Knobs}/{@code ConfigKeys}
 * read as one expression.
 *
 * @param status          lifecycle status (drives rendering + superseded report)
 * @param reloadable      model-only flag for CS4 hot-reload (no behavior yet)
 * @param aliases         legacy dotted paths that resolve to this key when the
 *                        canonical path is absent in the operator file
 * @param comment         operator-facing comment lines rendered above the key
 * @param supersededBy    for SUPERSEDED keys: where the behavior lives now
 *                        (named in the boot WARN and the annotation comment)
 */
public record KnobMeta(
    KeyStatus status,
    boolean reloadable,
    List<String> aliases,
    List<String> comment,
    String supersededBy
) {

    public static KnobMeta active(String... comment) {
        return new KnobMeta(KeyStatus.ACTIVE, false, List.of(), List.of(comment), null);
    }

    public static KnobMeta superseded(String paperEquivalent, String... comment) {
        return new KnobMeta(KeyStatus.SUPERSEDED, false, List.of(), List.of(comment), paperEquivalent);
    }

    public static KnobMeta reserved(String... comment) {
        return new KnobMeta(KeyStatus.RESERVED, false, List.of(), List.of(comment), null);
    }

    public KnobMeta aliases(String... a) {
        return new KnobMeta(status, reloadable, List.of(a), comment, supersededBy);
    }

    /** Named withReloadable: a record's boolean component already owns the reloadable() accessor. */
    public KnobMeta withReloadable() {
        return new KnobMeta(status, true, aliases, comment, supersededBy);
    }

    /** Default meta for legacy no-meta constructors: ACTIVE, no comment. */
    static KnobMeta legacy() {
        return new KnobMeta(KeyStatus.ACTIVE, false, List.of(), List.of(), null);
    }
}
