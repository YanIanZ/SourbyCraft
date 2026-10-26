package dev.iyanz.sourbycraft.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Build 49 relayout of the two SourbyCraft configuration files, in two independent halves.
 *
 * <p><b>Rewriting</b> ({@link #plan} + {@link #apply}): once, at boot, the file on disk is brought
 * to the new layout: renamed keys move to their new table with their value and the current comment,
 * Aurora keys still kept in the utility file move to {@code aurora.yml} (unless it already sets them,
 * then the stale copy is dropped: {@code aurora.yml} always won), retired keys nothing reads are
 * removed, and emptied tables disappear. Every file that is rewritten is first copied to
 * {@code <file>.migrated} (never overwritten; {@code .migrated.1} and so on when taken), so no operator
 * data is lost. Nothing here changes a value. This half stays after the old names stopped being read,
 * so a server that skipped Build 49 still keeps its settings (migrate before remove).</p>
 *
 * <p><b>Reading</b> ({@link #stripLegacyNames}): since {@link #LEGACY_READS_REMOVED} an old key name
 * ({@code ui.*}, {@code misc.auto_update.*}, ...) is not read. It survives on disk only where the
 * rewrite could not run (a read-only directory) or where it was added back by hand; the snapshot
 * consumers read leaves it out, and the caller logs one WARN per old name per load.</p>
 */
public final class ConfigMigration {

    private ConfigMigration() {}

    /** Appended to a file's name for the copy taken before it is rewritten. */
    public static final String MIGRATED_SUFFIX = ".migrated";
    /** The first build that no longer reads the old key names (the one-time rewrite still renames them). */
    public static final String LEGACY_READS_REMOVED = "Build 50";

    // --------------------------------------------------------------------------------- reading

    /**
     * @param values  the snapshot without any old key name
     * @param ignored old names that were present, in table order; none of them was read
     */
    public record Stripped(ConfigSnapshot values, List<String> ignored) {
        public Stripped {
            ignored = List.copyOf(ignored);
        }
    }

    /** Leaves the old utility key names out of a flattened snapshot and lists them; pure. */
    public static Stripped stripLegacyNames(final ConfigSnapshot utility) {
        final Map<String, Object> values = new LinkedHashMap<>(utility.values());
        final List<String> ignored = new ArrayList<>();
        for (final String old : UtilityKeys.legacyToCurrent().keySet()) {
            if (values.remove(old) != null) ignored.add(old);
        }
        return new Stripped(new ConfigSnapshot(values), ignored);
    }

    // ------------------------------------------------------------------------------- rewriting

    /** One key moving from an old path to its current path, inside one file or across the two. */
    public record Move(String from, String to, Object value) {}

    /**
     * What {@link #apply} will do.
     *
     * @param renames       utility keys renamed inside the utility file
     * @param toAurora      keys leaving the utility file for {@code aurora.yml}
     * @param auroraRenames old Aurora key names renamed inside {@code aurora.yml}
     * @param retired       utility keys removed because nothing reads them any more
     * @param dropped       utility keys removed because their current name is already set (the copy that
     *                      never decided anything)
     */
    public record Plan(List<Move> renames, List<Move> toAurora, List<Move> auroraRenames, List<String> retired,
                       List<String> dropped) {
        public Plan {
            renames = List.copyOf(renames);
            toAurora = List.copyOf(toAurora);
            auroraRenames = List.copyOf(auroraRenames);
            retired = List.copyOf(retired);
            dropped = List.copyOf(dropped);
        }

        public static final Plan EMPTY = new Plan(List.of(), List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return this.renames.isEmpty() && this.toAurora.isEmpty() && this.auroraRenames.isEmpty()
                && this.retired.isEmpty() && this.dropped.isEmpty();
        }

        public boolean touchesUtility() {
            return !this.renames.isEmpty() || !this.toAurora.isEmpty() || !this.retired.isEmpty() || !this.dropped.isEmpty();
        }

        public boolean touchesAurora() {
            return !this.toAurora.isEmpty() || !this.auroraRenames.isEmpty();
        }
    }

    /**
     * Computes the rewrite from the two parsed files; reads only.
     *
     * @param utility the utility file
     * @param aurora  {@code aurora.yml}, or {@code null} when it could not be opened (Aurora keys then stay
     *                where they are: they are still read through the layering)
     */
    public static Plan plan(final UnmodifiableConfig utility, final @Nullable UnmodifiableConfig aurora) {
        final Map<String, Object> flat = ConfigSnapshot.copyOf(utility).values();
        final Map<String, Object> auroraFlat = aurora == null ? Map.of() : ConfigSnapshot.copyOf(aurora).values();

        final List<Move> renames = new ArrayList<>();
        final List<String> dropped = new ArrayList<>();
        for (final Map.Entry<String, String> rename : UtilityKeys.legacyToCurrent().entrySet()) {
            final String old = rename.getKey();
            if (!flat.containsKey(old)) continue;
            if (flat.containsKey(rename.getValue())) dropped.add(old);
            else renames.add(new Move(old, rename.getValue(), flat.get(old)));
        }

        final List<String> retired = new ArrayList<>();
        for (final String key : flat.keySet()) {
            for (final String prefix : UtilityKeys.RETIRED_PREFIXES) {
                if (key.equals(prefix) || key.startsWith(prefix + ".")) {
                    retired.add(key);
                    break;
                }
            }
        }

        final List<Move> toAurora = new ArrayList<>();
        if (aurora != null) {
            // current names first, so a legacy copy of the same key is the one dropped
            final List<String> owned = new ArrayList<>();
            for (final String key : flat.keySet()) if (AuroraKeys.owns(key) && key.startsWith("aurora.")) owned.add(key);
            for (final String key : flat.keySet()) if (AuroraKeys.owns(key) && !key.startsWith("aurora.")) owned.add(key);
            final Set<String> targets = new LinkedHashSet<>(auroraFlat.keySet());
            for (final String key : owned) {
                final String target = AuroraKeys.currentPath(key);
                if (targets.add(target)) toAurora.add(new Move(key, target, flat.get(key)));
                else dropped.add(key);
            }
        }

        final List<Move> auroraRenames = new ArrayList<>();
        for (final ConfigKey key : AuroraKeys.KEYS) {
            for (final String legacy : key.legacyPaths()) {
                if (auroraFlat.containsKey(legacy) && !auroraFlat.containsKey(key.path())) {
                    auroraRenames.add(new Move(legacy, key.path(), auroraFlat.get(legacy)));
                }
            }
        }
        return new Plan(renames, toAurora, auroraRenames, retired, dropped);
    }

    /**
     * What {@link #apply} did.
     *
     * @param utilityBackup the copy of the utility file taken before it was rewritten, or {@code null}
     * @param auroraBackup  the copy of the Aurora file taken before it was rewritten, or {@code null}
     */
    public record Report(Plan plan, @Nullable Path utilityBackup, @Nullable Path auroraBackup) {

        public boolean rewroteSomething() {
            return !this.plan.isEmpty();
        }

        /** The one INFO line, naming what moved and where the previous files are. */
        public String summary() {
            final StringBuilder out = new StringBuilder("Migrated the configuration files to the Build 49 layout:");
            final Plan p = this.plan;
            if (!p.renames().isEmpty()) out.append(' ').append(p.renames().size()).append(" key(s) renamed;");
            if (!p.toAurora().isEmpty()) out.append(' ').append(p.toAurora().size()).append(" Aurora key(s) moved to ").append(AuroraKeys.FILE).append(';');
            if (!p.auroraRenames().isEmpty()) out.append(' ').append(p.auroraRenames().size()).append(" key(s) renamed inside ").append(AuroraKeys.FILE).append(';');
            if (!p.retired().isEmpty()) out.append(" retired key(s) removed: ").append(p.retired()).append(';');
            if (!p.dropped().isEmpty()) out.append(" stale duplicate(s) dropped (the current key already decided): ").append(p.dropped()).append(';');
            out.append(" values unchanged.");
            if (this.utilityBackup != null) out.append(" Previous file kept as ").append(this.utilityBackup.getFileName()).append('.');
            if (this.auroraBackup != null) out.append(" Previous Aurora file kept as ").append(this.auroraBackup.getFileName()).append('.');
            return out.toString();
        }
    }

    /**
     * Performs {@code plan}: backs up, rewrites, saves. Comments of renamed keys are replaced by the
     * current documentation; emptied tables are removed.
     *
     * @throws IOException when a backup cannot be written; nothing has been changed in that case
     */
    public static Report apply(final Plan plan, final CommentedFileConfig utility, final Path utilityPath,
                               final @Nullable CommentedFileConfig aurora, final Path auroraPath) throws IOException {
        if (plan.isEmpty()) return new Report(plan, null, null);
        final boolean auroraTouched = plan.touchesAurora() && aurora != null;

        // Backups before any change, so a failure here changes nothing.
        final Path utilityBackup = plan.touchesUtility() ? backup(utilityPath) : null;
        final Path auroraBackup = auroraTouched ? backup(auroraPath) : null;

        for (final Move rename : plan.renames()) {
            utility.set(split(rename.to()), rename.value());
            final ConfigKey key = UtilityKeys.byPath(rename.to());
            if (key != null) utility.setComment(split(rename.to()), key.documentation());
            utility.remove(split(rename.from()));
            utility.removeComment(split(rename.from()));
        }
        for (final String key : plan.retired()) {
            utility.remove(split(key));
            utility.removeComment(split(key));
        }
        for (final String key : plan.dropped()) {
            utility.remove(split(key));
            utility.removeComment(split(key));
        }
        if (aurora != null) {
            for (final Move move : plan.toAurora()) {
                aurora.set(split(move.to()), move.value());
                final ConfigKey key = AuroraKeys.byPath(move.to());
                if (key != null) aurora.setComment(split(move.to()), key.documentation());
                utility.remove(split(move.from()));
                utility.removeComment(split(move.from()));
            }
            for (final Move rename : plan.auroraRenames()) {
                aurora.set(split(rename.to()), rename.value());
                final ConfigKey key = AuroraKeys.byPath(rename.to());
                if (key != null) aurora.setComment(split(rename.to()), key.documentation());
                aurora.remove(split(rename.from()));
                aurora.removeComment(split(rename.from()));
            }
        }
        if (plan.touchesUtility()) {
            pruneEmptyTables(utility);
            utility.save();
        }
        if (auroraTouched) {
            pruneEmptyTables(aurora);
            aurora.save();
        }
        return new Report(plan, utilityBackup, auroraBackup);
    }

    /**
     * Copies {@code file} to the first free {@code <file>.migrated[.n]} name; {@code null} when it does not
     * exist or is empty (a file the loader has just created holds nothing worth keeping).
     */
    static @Nullable Path backup(final Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) return null;
        final Path target = freeMigratedName(file);
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    /** {@code <file>.migrated}, or {@code .migrated.1}, {@code .migrated.2} ... when that is taken. */
    static Path freeMigratedName(final Path file) {
        final Path first = file.resolveSibling(file.getFileName() + MIGRATED_SUFFIX);
        if (!Files.exists(first)) return first;
        for (int n = 1; ; n++) {
            final Path candidate = file.resolveSibling(file.getFileName() + MIGRATED_SUFFIX + "." + n);
            if (!Files.exists(candidate)) return candidate;
        }
    }

    /** Removes tables left without any key (nightconfig would otherwise write them as {@code x = {}}). */
    static void pruneEmptyTables(final CommentedConfig config) {
        final List<String> keys = new ArrayList<>();
        for (final UnmodifiableConfig.Entry entry : config.entrySet()) keys.add(entry.getKey());
        for (final String key : keys) {
            final Object value = config.getRaw(List.of(key));
            if (value instanceof CommentedConfig child) {
                pruneEmptyTables(child);
                if (child.isEmpty()) {
                    config.remove(List.of(key));
                    config.removeComment(List.of(key));
                }
            }
        }
    }

    private static List<String> split(final String path) {
        return List.of(path.split("\\."));
    }
}
