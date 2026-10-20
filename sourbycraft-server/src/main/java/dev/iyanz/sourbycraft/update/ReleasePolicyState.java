package dev.iyanz.sourbycraft.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What this server has learned from signed release manifests, kept in
 * {@code auto_update/}{@value #FILE} so a replayed older manifest cannot undo it:
 * the highest {@code policy_revision} accepted per release tag, and every tag any authentic manifest
 * has yanked. Yanks only accumulate; revisions only rise.
 *
 * <p><b>Missing file</b> (first check on a key-enforcing build, or the operator deleted it): start
 * empty — trust on first use. There is no earlier knowledge to protect, and refusing would block
 * every fresh install forever; the replay window for such a server is bounded by each manifest's
 * {@code expires_at}. Deleting the file needs write access to the server directory, which already
 * allows replacing the jar itself.
 *
 * <p><b>Corrupt file</b> (unparseable, wrong shape, over the bounds): {@link CorruptStateException}
 * and the check fails closed ({@code MANIFEST_REJECTED}). Reading it as empty would silently forget
 * yanks and revisions, which is exactly what a replay needs. The operator decides: deleting the file
 * is an explicit reset to trust-on-first-use, logged as such by the updater.
 *
 * <p>Writes go to a unique temporary file and are moved over the state atomically; concurrent
 * checks (scheduled + {@code /update}) are serialised by one lock and every save re-reads and merges
 * (max revision, union of yanks), so neither check can lower what the other recorded.
 */
public final class ReleasePolicyState {

    static final String FILE = "release-policy.json";
    static final int MAX_ENTRIES = 4096;
    private static final Object LOCK = new Object();

    private final Map<String, Long> revisions;
    private final Set<String> yanked;

    private ReleasePolicyState(Map<String, Long> revisions, Set<String> yanked) {
        this.revisions = revisions;
        this.yanked = yanked;
    }

    public static ReleasePolicyState empty() {
        return new ReleasePolicyState(new TreeMap<>(), new TreeSet<>());
    }

    /** The persisted state is unreadable; the check must fail closed until the operator resets it. */
    public static final class CorruptStateException extends IOException {
        public CorruptStateException(String message) {
            super(message);
        }
    }

    /** Load {@code dir/}{@value #FILE}: empty when absent, {@link CorruptStateException} when unreadable. */
    public static ReleasePolicyState load(Path dir) throws IOException {
        synchronized (LOCK) {
            return read(dir.resolve(FILE));
        }
    }

    private static ReleasePolicyState read(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return empty();
        }
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) throw new CorruptStateException(file + " is not a JSON object");
            JsonObject o = root.getAsJsonObject();
            if (!o.has("schema") || o.get("schema").getAsInt() != 1) throw new CorruptStateException(file + " has an unknown schema");
            JsonObject revs = o.getAsJsonObject("revisions");
            JsonArray yanks = o.getAsJsonArray("yanked");
            if (revs == null || yanks == null || revs.size() > MAX_ENTRIES || yanks.size() > MAX_ENTRIES) {
                throw new CorruptStateException(file + " lacks revisions/yanked or exceeds " + MAX_ENTRIES + " entries");
            }
            Map<String, Long> revisions = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : revs.entrySet()) {
                long v = e.getValue().getAsLong();
                if (v < 1) throw new CorruptStateException(file + " holds a non-positive revision");
                revisions.put(e.getKey(), v);
            }
            Set<String> yanked = new TreeSet<>();
            for (JsonElement e : yanks) {
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
                    throw new CorruptStateException(file + " holds a non-string yank");
                }
                yanked.add(e.getAsString());
            }
            return new ReleasePolicyState(revisions, yanked);
        } catch (JsonParseException | IllegalStateException | ClassCastException | NumberFormatException
                 | UnsupportedOperationException e) {
            throw new CorruptStateException(file + " is not readable state: " + e.getMessage());
        }
    }

    /** Highest accepted revision for {@code tag}, 0 when none was ever accepted. */
    public long revision(String tag) {
        return revisions.getOrDefault(tag, 0L);
    }

    public Set<String> yanked() {
        return Collections.unmodifiableSet(yanked);
    }

    /** Remember an accepted (fresh, authentic) revision; never lowers a stored one. */
    public void acceptRevision(String tag, long revision) {
        revisions.merge(tag, revision, Math::max);
    }

    /** Remember yanks from an authentic manifest (expired or older ones included: yanks only restrict). */
    public void addYanks(Set<String> tags) {
        yanked.addAll(tags);
    }

    /**
     * Merge this state into whatever is on disk now (max revision, union of yanks) and write the
     * result atomically. Entries beyond {@link #MAX_ENTRIES} are not written; the yank set then keeps
     * the lexicographically first tags (older releases sort first in practice) and the caller logs it.
     */
    public void save(Path dir) throws IOException {
        synchronized (LOCK) {
            Path file = dir.resolve(FILE);
            ReleasePolicyState onDisk = read(file);
            onDisk.revisions.forEach(this::acceptRevision);
            yanked.addAll(onDisk.yanked);
            JsonObject o = new JsonObject();
            o.addProperty("schema", 1);
            JsonObject revs = new JsonObject();
            revisions.entrySet().stream().limit(MAX_ENTRIES).forEach(e -> revs.addProperty(e.getKey(), e.getValue()));
            o.add("revisions", revs);
            JsonArray yanks = new JsonArray();
            yanked.stream().limit(MAX_ENTRIES).forEach(yanks::add);
            o.add("yanked", yanks);
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, FILE + ".", ".part");
            try {
                Files.writeString(temp, o.toString() + "\n", StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }
}
