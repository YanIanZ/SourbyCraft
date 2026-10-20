package dev.iyanz.sourbycraft.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The signed release manifest (TODO Inovasi "signed release manifest with staged rollout % and
 * yank list"; design in {@code docs/development/AUTO-UPDATER.md}). One per GitHub release, as the
 * asset {@value #ASSET_NAME} plus its detached Ed25519 signature {@value #SIGNATURE_ASSET_NAME},
 * signed with the same SEC-2 key as the jar. The signature covers the exact file bytes, so no JSON
 * canonicalisation is involved: what was signed is what is parsed.
 *
 * <pre>{@code
 * {
 *   "schema": 1,
 *   "tag": "v26.2-r48",
 *   "asset": "SourbyCraft-26.2-REL.jar",
 *   "sha256": "<hex SHA-256 of that jar asset>",
 *   "rollout_percent": 25,
 *   "yanked": ["v26.2-r47"],
 *   "policy_revision": 1792454400,
 *   "issued_at": "2026-10-20T00:00:00Z",
 *   "expires_at": "2027-01-18T00:00:00Z"
 * }
 * }</pre>
 *
 * <p>{@code tag}, {@code asset} and {@code sha256} bind the manifest to one release and one jar: a
 * validly signed manifest of another release (or of another build of the same release) attached
 * to this one is refused. {@code policy_revision} (a positive integer the signer increases every
 * time it signs a new policy for a tag; {@code scripts/release_manifest.py} uses the signing time in
 * epoch seconds) and {@code expires_at} give freshness: {@link ReleasePolicyState} remembers the
 * highest revision accepted per tag, and a lower revision or an expired manifest can no longer make
 * its release installable (its yanks still count). {@code issued_at} is informational. Unknown
 * fields are ignored; an unknown {@code schema} is refused (a newer format must not be read with
 * older rules). The text must be strict JSON with no duplicate member name anywhere, compared after
 * escape decoding (a name spelled with a JSON unicode escape duplicates its plain spelling): a signed
 * but ambiguous document is refused rather than resolved by "last one wins".
 *
 * @param tag            the release tag this manifest belongs to
 * @param asset          the jar asset name it describes
 * @param sha256         lower-case hex SHA-256 of that jar
 * @param rolloutPercent 0..100: share of servers that may take this release (see {@link RolloutBucket})
 * @param yanked         release tags that must never be staged (this one included, if listed)
 * @param policyRevision signer-assigned revision, higher = newer policy for this tag
 * @param expiresAt      instant after which this manifest cannot make its release installable
 */
public record ReleaseManifest(String tag, String asset, String sha256, int rolloutPercent, Set<String> yanked,
                              long policyRevision, Instant expiresAt) {

    public static final String ASSET_NAME = "sourbycraft-release-manifest.json";
    public static final String SIGNATURE_ASSET_NAME = ASSET_NAME + ReleaseSignatureVerifier.SIGNATURE_SUFFIX;
    /** Bound on the manifest download; a real one is a few hundred bytes. */
    public static final int MAX_BYTES = 64 * 1024;
    /** Bound on the yank list, so a hostile-but-signed file cannot make a check allocate without limit. */
    public static final int MAX_YANKED = 4096;
    static final int SCHEMA = 1;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ReleaseManifest {
        yanked = Set.copyOf(yanked);
    }

    /** Thrown for any manifest that is unreadable, malformed or not bound to the release at hand. */
    public static final class InvalidManifestException extends Exception {
        public InvalidManifestException(String message) {
            super(message);
        }
    }

    /** Parse already signature-verified bytes. Strict: every required field, exact types and ranges. */
    public static ReleaseManifest parse(byte[] bytes) throws InvalidManifestException {
        if (bytes.length > MAX_BYTES) throw new InvalidManifestException("manifest is " + bytes.length + " bytes");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidManifestException("manifest is not UTF-8");
        }
        rejectAmbiguousJson(text);
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new InvalidManifestException("manifest is not JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) throw new InvalidManifestException("manifest is not a JSON object");
        JsonObject o = root.getAsJsonObject();
        int schema = integer(o, "schema");
        if (schema != SCHEMA) throw new InvalidManifestException("unsupported manifest schema " + schema);
        String tag = string(o, "tag");
        String asset = string(o, "asset");
        String sha = string(o, "sha256").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha).matches()) throw new InvalidManifestException("sha256 is not 64 hex characters");
        int rollout = integer(o, "rollout_percent");
        if (rollout < 0 || rollout > 100) throw new InvalidManifestException("rollout_percent " + rollout + " is outside 0..100");
        JsonElement y = o.get("yanked");
        if (y == null || !y.isJsonArray()) throw new InvalidManifestException("yanked is not an array");
        JsonArray arr = y.getAsJsonArray();
        if (arr.size() > MAX_YANKED) throw new InvalidManifestException("yanked lists " + arr.size() + " tags");
        Set<String> yanked = new LinkedHashSet<>();
        for (JsonElement e : arr) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
                throw new InvalidManifestException("yanked holds a non-string or blank entry");
            }
            yanked.add(e.getAsString().trim());
        }
        long revision = longInteger(o, "policy_revision");
        if (revision < 1) throw new InvalidManifestException("policy_revision must be a positive integer");
        Instant expires;
        try {
            expires = Instant.parse(string(o, "expires_at"));
        } catch (DateTimeParseException e) {
            throw new InvalidManifestException("expires_at is not an ISO-8601 instant");
        }
        return new ReleaseManifest(tag, asset, sha, rollout, yanked, revision, expires);
    }

    /** True once {@code now} is past {@link #expiresAt()}. */
    public boolean expiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * Walk the text with a strict streaming reader and refuse duplicate member names in any object
     * (names compared as decoded strings, so escaped spellings collide) and anything after the
     * top-level value. Gson's tree parser is lenient and keeps the last duplicate; this runs first.
     */
    static void rejectAmbiguousJson(String text) throws InvalidManifestException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            walk(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new InvalidManifestException("manifest has data after the JSON value");
            }
        } catch (IOException | IllegalStateException | JsonParseException e) {
            throw new InvalidManifestException("manifest is not strict JSON: " + e.getMessage());
        }
    }

    private static void walk(JsonReader reader, int depth) throws IOException, InvalidManifestException {
        if (depth > 32) throw new InvalidManifestException("manifest nests deeper than 32 levels");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new InvalidManifestException("duplicate member name \"" + name + "\"");
                    walk(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) walk(reader, depth + 1);
                reader.endArray();
            }
            default -> reader.skipValue();
        }
    }

    /** Only the tag, for a release that has no jar asset: its signed yanks still count (never selectable). */
    public void requireTagBound(String releaseTag) throws InvalidManifestException {
        if (!tag.equals(releaseTag)) {
            throw new InvalidManifestException("manifest is for " + tag + ", not " + releaseTag);
        }
    }

    /**
     * Require that this manifest describes exactly this release: same tag, same jar asset name and
     * the same SHA-256 the release publishes for that asset.
     */
    public void requireBoundTo(String releaseTag, String assetName, String assetSha256) throws InvalidManifestException {
        if (!tag.equals(releaseTag)) {
            throw new InvalidManifestException("manifest is for " + tag + ", not " + releaseTag);
        }
        if (!asset.equals(assetName)) {
            throw new InvalidManifestException("manifest describes asset " + asset + ", the release ships " + assetName);
        }
        if (assetSha256 == null || !sha256.equalsIgnoreCase(assetSha256)) {
            throw new InvalidManifestException("manifest sha256 differs from the release asset digest");
        }
    }

    private static String string(JsonObject o, String key) throws InvalidManifestException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) {
            throw new InvalidManifestException(key + " is missing or not a string");
        }
        return e.getAsString().trim();
    }

    private static long longInteger(JsonObject o, String key) throws InvalidManifestException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new InvalidManifestException(key + " is missing or not a number");
        }
        try {
            return new BigDecimal(e.getAsJsonPrimitive().getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new InvalidManifestException(key + " is not an integer");
        }
    }

    private static int integer(JsonObject o, String key) throws InvalidManifestException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new InvalidManifestException(key + " is missing or not a number");
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            return new BigDecimal(p.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new InvalidManifestException(key + " is not an integer");
        }
    }
}
