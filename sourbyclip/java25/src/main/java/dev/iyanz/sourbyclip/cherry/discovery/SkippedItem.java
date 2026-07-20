package dev.iyanz.sourbyclip.cherry.discovery;

/**
 * Cherry — one discovery-time item that was found but deliberately NOT loaded, plus a human-readable
 * reason. Every skip Cherry's discovery pipeline performs is recorded as one of these rather than
 * silently dropped, so {@link dev.iyanz.sourbyclip.cherry.CherryStatus} and a future {@code /cherry}
 * command can show an operator exactly what did not load and why — this is the data behind goal 1's
 * "log a clear WARNING, skip that one item" discipline.
 *
 * @param jarName human-readable source, usually the plugin jar's file name (e.g. {@code "MyPlugin.jar"})
 * @param pluginName the plugin/mod name if one could be determined, else the same value as
 *                    {@code jarName}
 * @param item     what was being loaded when it was skipped, e.g. {@code "mixin config myplugin.mixins.json"}
 *                 or {@code "access-transformer widener.at"}
 * @param reason   a short, human-readable explanation, e.g. {@code "entry missing from jar"} or
 *                 {@code "malformed JSON: ..."}
 */
public record SkippedItem(String jarName, String pluginName, String item, String reason) {

    /** @return a single-line, log-ready rendering: {@code "<pluginName> (<jarName>): <item> - <reason>"}. */
    public String describe() {
        if (pluginName.equals(jarName)) {
            return jarName + ": " + item + " - " + reason;
        }
        return pluginName + " (" + jarName + "): " + item + " - " + reason;
    }
}
