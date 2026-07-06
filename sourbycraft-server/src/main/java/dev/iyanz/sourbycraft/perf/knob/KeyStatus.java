package dev.iyanz.sourbycraft.perf.knob;

/**
 * Lifecycle status of a config key. Drives operator-yml rendering and the
 * boot-time superseded-key report.
 *
 * <ul>
 *   <li>{@code ACTIVE} — key drives engine behavior; rendered in fresh files.</li>
 *   <li>{@code SUPERSEDED} — key is loaded but drives nothing (moonrise / Paper
 *       owns the behavior). Never rendered fresh; preserved with annotation if
 *       the operator already has it; boot WARN when set non-default.</li>
 *   <li>{@code RESERVED} — key parks a future feature (e.g. item pool v2).
 *       Same rendering rules as SUPERSEDED.</li>
 * </ul>
 */
public enum KeyStatus {
    ACTIVE, SUPERSEDED, RESERVED
}
