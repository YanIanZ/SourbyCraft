package dev.iyanz.sourbyclip.cherry.manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

/**
 * Cherry — the Gson deserialization target for a Fabric-ecosystem {@code fabric.mod.json}, limited
 * to the handful of fields Cherry's Fabric bridge actually reads.
 *
 * <p>This is deliberately a <b>partial</b> reproduction of the Fabric mod-metadata schema (see
 * <a href="https://fabricmc.net/wiki/documentation:fabric_mod_json_spec">the Fabric wiki</a> for the
 * full spec): Cherry has no Fabric mod loader and does not run mod entrypoints, dependencies,
 * jars-in-jar, or client assets, so none of that is modeled here. Only the two fields relevant to
 * loading server-side mixins/access-wideners into the Leaves/SpongePowered Mixin environment are
 * read:
 * <ul>
 *   <li>{@code mixins} — a heterogeneous array of either bare config-file-name strings or
 *       {@code {"config": ..., "environment": ...}} objects, normalized by {@link #mixinEntries()}
 *       into a uniform {@link FabricMixinEntry} list.</li>
 *   <li>{@code accessWidener} — a single Fabric access-widener file name, resolved relative to the
 *       jar root, bridged into the same Leaves {@code AccessWidenerManager} pipeline that a
 *       {@code cherry-plugin.json}'s {@code mixin.access-widener} field feeds.</li>
 * </ul>
 *
 * <p>See {@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge} for how discovered entries
 * are turned into an extracted, classpath-loadable jar, and the repository README's "Fabric
 * support" section for the honest scope of what this does and does not run.
 */
public final class FabricModManifest {

    private String id;
    private List<JsonElement> mixins;
    private String accessWidener;

    /** @return the Fabric mod id (used for logging/namespacing), or {@code null} if absent. */
    public String getId() {
        return id;
    }

    /** @return the raw {@code accessWidener} file name declared in the manifest, or {@code null}. */
    public String getAccessWidener() {
        return accessWidener;
    }

    /**
     * Normalize the raw {@code mixins} array into a uniform list of {@link FabricMixinEntry}.
     * Tolerant of every malformed shape it can identify: a missing/{@code null} {@code mixins}
     * field yields an empty list rather than {@code null}; an array element that is neither a JSON
     * string nor a JSON object (or an object missing its required {@code config} string) is skipped
     * silently — the caller ({@link dev.iyanz.sourbyclip.cherry.discovery.CherryDiscovery}) is
     * responsible for surfacing a skip reason with plugin/jar context, which this class does not
     * have.
     *
     * @return the normalized entries, in declaration order; never {@code null}
     */
    public List<FabricMixinEntry> mixinEntries() {
        if (mixins == null || mixins.isEmpty()) {
            return List.of();
        }
        List<FabricMixinEntry> entries = new ArrayList<>(mixins.size());
        for (JsonElement element : mixins) {
            FabricMixinEntry entry = normalize(element);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private static FabricMixinEntry normalize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String config = element.getAsString();
                return config.isBlank() ? null : new FabricMixinEntry(config, "*");
            }
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                JsonElement configElement = obj.get("config");
                if (configElement == null || !configElement.isJsonPrimitive() || !configElement.getAsJsonPrimitive().isString()) {
                    return null;
                }
                String config = configElement.getAsString();
                if (config.isBlank()) {
                    return null;
                }
                JsonElement envElement = obj.get("environment");
                String environment = (envElement != null && envElement.isJsonPrimitive() && envElement.getAsJsonPrimitive().isString())
                    ? envElement.getAsString()
                    : "*";
                return new FabricMixinEntry(config, environment.isBlank() ? "*" : environment);
            }
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            // Malformed element shape (e.g. a JSON array nested where a primitive was expected) -
            // treated the same as "unparseable", never thrown up to the caller.
        }
        return null;
    }
}
