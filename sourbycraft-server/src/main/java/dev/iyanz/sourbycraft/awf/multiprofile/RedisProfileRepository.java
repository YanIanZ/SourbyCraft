package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Domain;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.BlobRef;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.SnapshotRef;
import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.fabric.CoopClaimFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload;
import dev.iyanz.sourbycraft.awf.redis.fabric.SessionFabric;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ProfileRepository} in the Aurora Redis Fabric, shared by every server on one Redis.
 *
 * <p><b>Fenced commits.</b> Every mutation is one Lua script. Before it changes anything the script
 * checks, atomically inside Redis, (1) the claim of the {@link ProfileWriteGuard} against the profile's
 * session hash that {@link SessionFabric} keeps (the same {@code node/incarnation/owner} session, owner
 * token and epoch, and {@code expiresAt} later than Redis {@code TIME}) and (2) the stored revision
 * against the expected one. Only then does it apply every change of that mutation; otherwise it changes
 * nothing and replies FENCED or STALE, which become {@link ProfileErrorCode#OWNERSHIP_CONFLICT} and
 * {@link ProfileErrorCode#STALE_STATE}. A writer whose lease lapsed or was taken over can therefore no
 * longer commit, whatever it validated before, and a refused write is never retried here. A write made
 * without a claim (creating a profile, account bookkeeping) is checked by revision only; a journal phase
 * written before a switch holds any claim carries no revision and is not checked at all (only that the
 * transition belongs to that player). The scripts start with the {@code #!lua} flag line, so Redis
 * refuses one up front when it is out of memory instead of failing half-way; that needs Redis 7.0.</p>
 *
 * <p><b>Format 2 (co-op, Build 51).</b> The store carries a format marker in the {@code meta} hash
 * ({@link StoreFormat}); it is read at open and inside every write script. In a format-1 store the
 * format-1 methods behave as in PR #62 and every format-2 method refuses with {@code MIGRATION_REQUIRED}.
 * In a format-2 store the format-1 record writers ({@link #saveAccount}, {@link #saveProfile},
 * {@link #saveBinding}, {@link #publishSnapshot}, {@link #deleteProfileData}) and the format-1 snapshot
 * reads refuse with {@code STORE_FORMAT_REFUSED}; the records are format-2 frames in the same keys, and
 * the format-2 methods of {@link ProfileRepository} are the write path. Each of them is one script
 * ({@link RedisProfileScripts}) that, before its first write, checks the gate, resolves the operation id
 * against the stored receipts (a replay returns the stored receipt, the same id with another digest is
 * refused), validates the typed {@code P}/{@code Q} claims against the {@link CoopClaimFabric} hashes
 * (exact session, fence, unexpired by Redis {@code TIME}), compares every expected revision of the
 * guard's vector and the active-profile predicate, and then applies all changes and the receipt. A
 * journal or legacy archive written in a format-2 store is a format-2 frame fenced by the guard's claim,
 * which may be a session claim or a co-op claim. While a migration is in progress only writes made under
 * a {@link CoopOperation.Kind#MIGRATION} guard pass the gate; reads then accept frames of either format,
 * so an interrupted migration can be read and resumed.</p>
 *
 * <p><b>Intents.</b> {@link #prepareIntent} copies the staged after-images ({@link #stageAfterImage})
 * into the operation's blob hash, writes the PREPARED intent and installs the reservations (each
 * reserved record re-encoded at revision + 1 with {@code pendingIntent}) in one script.
 * {@link #commitIntent} re-checks claims, the post-reservation vector and the reservations, then
 * installs the after-images, releases the other reservations, moves the pins and indexes, writes the
 * receipt and the FINALIZED intent in one script: in Redis COMMITTED and FINALIZED are one atomic step.
 * {@link #abortIntent} clears exactly the operation's reservations. A lost reply is reconciled by
 * re-reading the receipt or intent, and an applied write whose {@code WAITAOF} was not confirmed is
 * reported as {@link CommitResult.Status#UNKNOWN}, never as applied.</p>
 *
 * <p><b>Snapshots</b> (up to 8 MiB) are first written alone under a unique staging key with a TTL; the
 * publish script moves the staged record into the stream's snapshot hash and advances the head in the
 * same atomic step. A crash in between leaves an orphan that expires, never a half-published snapshot.
 * Retention prunes inside that script and keeps every snapshot that a pending intent of any member pins
 * ({@code pins:} hash with reference counts), or, in format 1, that a pending journal of the account
 * names.</p>
 *
 * <p><b>Records</b> are the framed, checksummed records of {@link FileProfileRepository}
 * ({@link ProfileRecords}), with the same size bounds, verified on every read. Every read of a record
 * value is bounded by its type's limit through {@link RedisClient#callBounded}: an oversized or growing
 * value fails the read before allocation.</p>
 *
 * <p><b>Keys</b>, under the fabric namespace plus {@code mp:}: {@code meta}, {@code account:<player>},
 * {@code profile:<profile>}, {@code binding:<profile>}, {@code snaps:<profile>} / {@code snapidx:<profile>}
 * (format 1), {@code legacy:<player>}, {@code stage:<snapshot>}, {@code journals:<player>},
 * {@code jmeta:<player>}, {@code journal-player}, {@code journal-pending}, {@code journal-failed}; format 2
 * adds {@code names:<account>}, {@code shared:<profile>}, {@code head:<profile>:<member>:<epoch>},
 * {@code msnaps:} / {@code msnapidx:} / {@code pins:} per stream, {@code intent:<op>}, {@code blobs:<op>},
 * {@code stage2:<sha256>}, {@code receipts:<resource>} / {@code rdigest:<resource>}, {@code invite:},
 * {@code vote:}, {@code salvage:} and {@code idx:<kind>:<scope>} sorted sets. Claim hashes are the
 * {@link SessionFabric}'s and the {@link CoopClaimFabric}'s. Every key a script touches is passed to it
 * as a key. One commit touches keys of several profiles and claims, which a Redis Cluster puts in
 * different slots, so a cluster is refused at startup.</p>
 *
 * <p><b>Durability is Redis's.</b> {@link #open} reads {@code INFO} and refuses a replica, a cluster,
 * Redis before 7.0 and, while {@code redis.require-aof} is true, {@code appendonly no}. With
 * {@code appendfsync everysec} up to about one second of acknowledged writes can be lost when Redis
 * crashes, and an asynchronous replica promoted on failover can lose acknowledged writes and move session
 * epochs back: one primary is the supported topology. With {@code redis.wait-for-aof} the writes a later
 * step relies on are followed by {@code WAITAOF 1 0} on the same connection; a format-1 write Redis does
 * not confirm in time is still applied, and is logged and counted; a format-2 write that is not confirmed
 * is reported as UNKNOWN so no effect follows it.</p>
 *
 * <p><b>Offline migration</b> ({@link ProfileMigrationStore}). Redis has no migration lock: the
 * operator stops every node first, {@link #requireNoOtherWriter} refuses what it can observe (a live
 * session or co-op claim of another process), the pre-migration export goes to
 * {@code migration.backup-dir} ({@link RedisProfileBackup}), and every migration write is a script that
 * re-checks the {@code meta} marker says IN_PROGRESS. Migration commits store no receipt: one migration
 * operation converts many records of one resource, and a receipt would make the second one a replay.</p>
 *
 * <p>Every method blocks on Redis and must run on an I/O worker. The repository uses the fabric's STORAGE
 * pool and never closes it.</p>
 */
public final class RedisProfileRepository implements ProfileRepository, ProfileMigrationStore {

    /** The layout this build writes into a store without a marker (PR #62). */
    private static final String FORMAT = "1";
    /** How long a staged snapshot or after-image waits for its commit before Redis drops it. */
    private static final long STAGE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** Journals one player may have; far above the few pending plus the terminal ones kept. */
    private static final int MAX_JOURNALS_PER_PLAYER = 64;
    /** Pending journals listed for status output. */
    private static final int MAX_LISTED_PENDING = 1024;
    /** Failed journals indexed for status output; older ones stay readable by player. */
    private static final int FAILED_LISTED = 64;
    private static final long UNCONFIRMED_WARN_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** Pending intents per player or profile (COOP-DESIGN §9). */
    private static final int MAX_PENDING_PER_SCOPE = 64;
    /** Pending intents in the global index (§9). */
    private static final int MAX_PENDING_GLOBAL = 1024;
    /** Open invites per profile and per invitee, salvage per profile (§9). */
    private static final int MAX_INDEXED_RECORDS = 64;
    /** Open votes per profile (§9). */
    private static final int MAX_OPEN_VOTES = 1;
    /** Pin entries one stream may hold before the pin read fails closed (64 pending intents of 64 refs). */
    private static final int MAX_PINS_PER_STREAM = 4096;
    /** Keys one enumeration ({@link #accountIds}, {@link #profileIds}) may return. */
    private static final int MAX_ENUMERATED = 1_000_000;
    /** Bytes of staged after-images one intent may reference (no snapshots: 64 control or shared records). */
    private static final long MAX_INTENT_BLOB_BYTES = 64L * ProfileRecords.MAX_SHARED_PAYLOAD;
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Shared by every format-1 write script. KEYS[1] is the meta hash; KEYS[2] is the claim hash of the
     * writer's claim (the meta hash again for a write without one): a session hash ({@code epoch}) or a
     * co-op claim hash ({@code fence}). ARGV[1] is the claim's session field ('' when there is no claim),
     * ARGV[2] its owner token and ARGV[3] its epoch or fence. {@code legacyOnly()} refuses a format-1
     * record write once the store is format 2.
     */
    private static final String HEADER = """
        #!lua
        local function fence()
          if ARGV[1] == '' then return nil end
          local cur = redis.call('HMGET', KEYS[2], 'session', 'owner', 'epoch', 'fence', 'expiresAt')
          local stored = cur[3] or cur[4] or '0'
          if cur[1] ~= ARGV[1] or cur[2] ~= ARGV[2] or tonumber(stored) ~= tonumber(ARGV[3]) then
            return {'FENCED', 'superseded', tostring(stored)}
          end
          local now = redis.call('TIME')
          if (tonumber(cur[5] or '0') or 0) <= tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) then
            return {'FENCED', 'expired', tostring(stored)}
          end
          return nil
        end
        local function legacyOnly()
          local format = redis.call('HGET', KEYS[1], 'format') or '1'
          if format ~= '1' then return {'FORMAT', 'the store is format ' .. format .. '; format-1 record writes are refused'} end
          return nil
        end
        local function formatIs(expected)
          local m = redis.call('HMGET', KEYS[1], 'format', 'migrationState')
          local format = m[1] or '1'
          if format ~= expected then
            return {'FORMAT', 'the store is format ' .. format .. ' but this write was encoded for format ' .. expected}
          end
          if (m[2] or '1') == '2' then return {'FORMAT', 'a format-2 migration is in progress; journal and archive writes are refused'} end
          return nil
        end
        local function stale(key, expected, absent)
          if expected == 'any' then return nil end
          local stored = redis.call('HGET', key, 'rev')
          local revision = absent
          if stored then revision = tonumber(stored) end
          if revision ~= tonumber(expected) then return {'STALE', tostring(revision)} end
          return nil
        end
        local function committed()
          redis.call('HINCRBY', KEYS[1], 'commits', 1)
        end
        """;

    /** KEYS[3] the record hash. ARGV[4] expected revision or 'any', [5] revision of no record, [6] new revision, [7] record. */
    private static final String SAVE_RECORD = HEADER + """
        local refused = legacyOnly() or fence() or stale(KEYS[3], ARGV[4], tonumber(ARGV[5]))
        if refused then return refused end
        redis.call('HSET', KEYS[3], 'rev', ARGV[6], 'rec', ARGV[7])
        committed()
        return {'OK'}
        """;

    /**
     * KEYS[3] profile, [4] staged snapshot, [5] snapshot hash, [6] snapshot index, [7] journal metadata of
     * the account. ARGV[4] expected profile revision or 'any', [5] new profile revision, [6] profile
     * record, [7] snapshot id, [8] snapshot revision, [9] snapshots kept.
     */
    private static final String PUBLISH_SNAPSHOT = HEADER + """
        local refused = legacyOnly() or fence() or stale(KEYS[3], ARGV[4], -1)
        if refused then return refused end
        local blob = redis.call('GET', KEYS[4])
        if not blob then return {'MISSING', 'the staged snapshot ' .. ARGV[7]} end
        redis.call('HSET', KEYS[5], ARGV[7], blob)
        redis.call('DEL', KEYS[4])
        redis.call('ZADD', KEYS[6], ARGV[8], ARGV[7])
        redis.call('HSET', KEYS[3], 'rev', ARGV[5], 'rec', ARGV[6], 'latest', ARGV[7])
        local pinned = {}
        local known = true
        for _, meta in ipairs(redis.call('HVALS', KEYS[7])) do
          local terminal, _, from, to = string.match(meta, '^(%d) (%d+) (%S+) (%S+)$')
          if not terminal then
            known = false
          elseif terminal == '0' then
            pinned[from] = true
            pinned[to] = true
          end
        end
        if known then
          local ids = redis.call('ZRANGE', KEYS[6], 0, -1)
          for i = 1, #ids - math.max(1, tonumber(ARGV[9])) do
            local id = ids[i]
            if id ~= ARGV[7] and not pinned[id] then
              redis.call('HDEL', KEYS[5], id)
              redis.call('ZREM', KEYS[6], id)
            end
          end
        end
        committed()
        return {'OK'}
        """;

    /**
     * KEYS[3] legacy hash of the player, [4] staged snapshot. ARGV[4] snapshot id, [5] the store format the
     * record was encoded for: the script re-reads the marker atomically, so a node holding a stale cached
     * marker (or writing while a migration is in progress) is refused.
     */
    private static final String SAVE_LEGACY = HEADER + """
        local refused = formatIs(ARGV[5]) or fence()
        if refused then return refused end
        local blob = redis.call('GET', KEYS[4])
        if not blob then return {'MISSING', 'the staged snapshot ' .. ARGV[4]} end
        redis.call('HSET', KEYS[3], ARGV[4], blob, 'latest', ARGV[4])
        redis.call('DEL', KEYS[4])
        committed()
        return {'OK'}
        """;

    /**
     * KEYS[3] journals of the player, [4] their metadata, [5] journal-to-player index, [6] pending set,
     * [7] failed index. ARGV[4] transition id, [5] player, [6] record, [7] '1' when terminal, [8] updated
     * ms, [9] from snapshot or '-', [10] to snapshot or '-', [11] '1' when failed, [12] terminal journals
     * kept, [13] journals a player may have, [14] failed journals indexed, [15] the store format the record
     * was encoded for (checked against the marker atomically; refused while a migration is in progress).
     */
    private static final String SAVE_JOURNAL = HEADER + """
        local refused = formatIs(ARGV[15]) or fence()
        if refused then return refused end
        local holder = redis.call('HGET', KEYS[5], ARGV[4])
        if holder and holder ~= ARGV[5] then
          return {'REFUSED', 'transition ' .. ARGV[4] .. ' belongs to player ' .. holder}
        end
        if redis.call('HEXISTS', KEYS[3], ARGV[4]) == 0 and redis.call('HLEN', KEYS[3]) >= tonumber(ARGV[13]) then
          return {'REFUSED', 'player ' .. ARGV[5] .. ' already has ' .. ARGV[13] .. ' transition journals'}
        end
        redis.call('HSET', KEYS[3], ARGV[4], ARGV[6])
        redis.call('HSET', KEYS[4], ARGV[4], ARGV[7] .. ' ' .. ARGV[8] .. ' ' .. ARGV[9] .. ' ' .. ARGV[10])
        redis.call('HSET', KEYS[5], ARGV[4], ARGV[5])
        if ARGV[7] == '1' then
          redis.call('SREM', KEYS[6], ARGV[4])
        else
          redis.call('SADD', KEYS[6], ARGV[4])
        end
        if ARGV[11] == '1' then
          redis.call('ZADD', KEYS[7], ARGV[8], ARGV[4])
          redis.call('ZREMRANGEBYRANK', KEYS[7], 0, -1 - tonumber(ARGV[14]))
        else
          redis.call('ZREM', KEYS[7], ARGV[4])
        end
        if ARGV[7] == '1' then
          local metas = redis.call('HGETALL', KEYS[4])
          local terminal = {}
          for i = 1, #metas, 2 do
            local done, updated = string.match(metas[i + 1], '^(%d) (%d+) ')
            if done == '1' then table.insert(terminal, {metas[i], tonumber(updated)}) end
          end
          local keep = tonumber(ARGV[12])
          if #terminal > keep then
            table.sort(terminal, function(a, b) return a[2] > b[2] end)
            for i = keep + 1, #terminal do
              local id = terminal[i][1]
              redis.call('HDEL', KEYS[3], id)
              redis.call('HDEL', KEYS[4], id)
              redis.call('HDEL', KEYS[5], id)
              redis.call('SREM', KEYS[6], id)
              redis.call('ZREM', KEYS[7], id)
            end
          end
        end
        committed()
        return {'OK'}
        """;

    /** KEYS as {@link #SAVE_JOURNAL}. ARGV[4] transition id, [5] player, [6] the store format this node read (checked atomically). */
    private static final String DELETE_JOURNAL = HEADER + """
        local refused = formatIs(ARGV[6]) or fence()
        if refused then return refused end
        if redis.call('HGET', KEYS[5], ARGV[4]) ~= ARGV[5] then return {'OK'} end
        redis.call('HDEL', KEYS[3], ARGV[4])
        redis.call('HDEL', KEYS[4], ARGV[4])
        redis.call('HDEL', KEYS[5], ARGV[4])
        redis.call('SREM', KEYS[6], ARGV[4])
        redis.call('ZREM', KEYS[7], ARGV[4])
        committed()
        return {'OK'}
        """;

    /** KEYS[3] binding, [4] snapshot hash, [5] snapshot index, [6] profile. */
    private static final String DELETE_PROFILE_DATA = HEADER + """
        local refused = legacyOnly() or fence()
        if refused then return refused end
        redis.call('DEL', KEYS[3], KEYS[4], KEYS[5])
        redis.call('HDEL', KEYS[6], 'latest')
        committed()
        return {'OK'}
        """;

    /** KEYS[1] meta. ARGV[1] start time. Refused once anything committed, an import finished, or the store left format 1. */
    private static final String IMPORT_BEGIN = """
        #!lua
        local format = redis.call('HGET', KEYS[1], 'format') or '1'
        if format ~= '1' then
          return {'REFUSED', 'the Redis repository is format ' .. format .. '; a format-1 file repository cannot be imported into it'}
        end
        local commits = tonumber(redis.call('HGET', KEYS[1], 'commits') or '0')
        if commits > 0 then
          return {'REFUSED', 'the Redis repository already holds profile data (' .. commits .. ' commits); nothing was copied'}
        end
        if redis.call('HGET', KEYS[1], 'imported') then
          return {'REFUSED', 'a file repository was already imported into this Redis repository; nothing was copied'}
        end
        redis.call('HSET', KEYS[1], 'import-started', ARGV[1])
        return {'OK'}
        """;

    /** Prefix of every import script: an import writes only while nothing else has committed. */
    private static final String IMPORT_HEADER = """
        #!lua
        if tonumber(redis.call('HGET', KEYS[1], 'commits') or '0') > 0 or redis.call('HGET', KEYS[1], 'imported') then
          return {'REFUSED', 'the Redis repository changed during the migration; nothing more was copied'}
        end
        """;

    /** KEYS[2] a hash. ARGV field and value pairs. */
    private static final String IMPORT_HASH = IMPORT_HEADER + """
        redis.call('HSET', KEYS[2], unpack(ARGV))
        return {'OK'}
        """;

    /**
     * KEYS[2] staged record, [3] the hash it moves to, [4] the hash whose 'latest' may name it, [5] the
     * index (or meta when none). ARGV[1] id, [2] revision for the index or '', [3] '1' to make it latest.
     */
    private static final String IMPORT_BLOB = IMPORT_HEADER + """
        local blob = redis.call('GET', KEYS[2])
        if not blob then return {'MISSING', 'the staged snapshot ' .. ARGV[1]} end
        redis.call('HSET', KEYS[3], ARGV[1], blob)
        redis.call('DEL', KEYS[2])
        if ARGV[2] ~= '' then redis.call('ZADD', KEYS[5], ARGV[2], ARGV[1]) end
        if ARGV[3] == '1' then redis.call('HSET', KEYS[4], 'latest', ARGV[1]) end
        return {'OK'}
        """;

    /**
     * KEYS[2] journals of the player, [3] their metadata, [4] journal-to-player index, [5] pending set,
     * [6] failed index. ARGV[1] transition id, [2] player, [3] record, [4] metadata, [5] '1' when
     * terminal, [6] '1' when failed, [7] updated ms.
     */
    private static final String IMPORT_JOURNAL = IMPORT_HEADER + """
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
        redis.call('HSET', KEYS[3], ARGV[1], ARGV[4])
        redis.call('HSET', KEYS[4], ARGV[1], ARGV[2])
        if ARGV[5] == '0' then redis.call('SADD', KEYS[5], ARGV[1]) end
        if ARGV[6] == '1' then redis.call('ZADD', KEYS[6], ARGV[7], ARGV[1]) end
        return {'OK'}
        """;

    /** KEYS[1] meta. ARGV[1] summary, [2] finish time. */
    private static final String IMPORT_FINISH = IMPORT_HEADER + """
        redis.call('HSET', KEYS[1], 'imported', ARGV[1], 'imported-at', ARGV[2])
        return {'OK'}
        """;

    /** How a write is acknowledged: from Redis's memory, or after {@code WAITAOF} when configured. */
    private enum Ack { MEMORY, AOF }

    private final RedisClient client;
    private final SessionFabric sessions;
    private final CoopClaimFabric coop;
    private final String namespace;
    private final Durability durability;
    /** {@code redis.wait-for-aof} as configured; {@link #waitForAof} is the effective value (the format-1 path may turn it off). */
    private final boolean waitForAofConfigured;
    private volatile boolean waitForAof;
    /** Why this Redis cannot answer {@code WAITAOF} (probed once at open), or {@code null} when it can or it is not requested. */
    private volatile String waitAofUnsupported;
    /** The slot limit a reservation gives an account without a record ({@link #attachDefaultSlots}). */
    private volatile int defaultSlots = PlayerAccount.DEFAULT_SLOTS;
    private final AtomicLong unconfirmed = new AtomicLong();
    private final AtomicLong lastUnconfirmedWarn = new AtomicLong(System.nanoTime() - UNCONFIRMED_WARN_NANOS);
    /** Why every write is refused: unmigrated profiles in the file repository. {@code null} when writable. */
    private volatile String writesRefused;
    /** Journals the last {@link #transitions()} could not read, for status output. */
    private volatile List<String> unreadable = List.of();
    /** The store format read at open and refreshed by {@link #storeFormat()} / {@link #writeStoreFormat}. */
    private volatile StoreFormat.Marker marker = StoreFormat.Marker.legacy();
    private volatile boolean closed;

    /** The profile type definitions kept in this store's namespace ({@code <prefix>types}; Build 54, the same key {@code ProfileTypes.open} uses). */
    public ProfileTypesStore typesStore() {
        return ProfileTypesStore.redis(this.client, this.namespace + "types");
    }

    private RedisProfileRepository(final RedisClient client, final SessionFabric sessions, final String namespace,
                                   final Durability durability, final boolean waitForAof) {
        this.client = client;
        this.sessions = sessions;
        this.coop = sessions.coopClaims();
        this.namespace = namespace;
        this.durability = durability;
        this.waitForAofConfigured = waitForAof;
        this.waitForAof = waitForAof;
    }

    /** The slot limit a reservation gives an account that has no record yet ({@code aurora.awf.multiprofile.default-slots}). */
    public void attachDefaultSlots(final int defaultSlots) {
        if (defaultSlots < 1) throw new IllegalArgumentException("default slots " + defaultSlots);
        this.defaultSlots = defaultSlots;
    }

    /**
     * Opens the repository of a running fabric. Refuses (with the reason) a Redis it cannot run on: a
     * replica, a cluster, a version before 7.0, {@code appendonly no} while {@code require-aof} is
     * true, or a repository written by a newer build (format or schema). A store whose format-2
     * migration is in progress opens, with every non-migration write refused by the gate. When the
     * repository is still format 1 and empty and the file repository in {@code fileDirectory} holds
     * profiles, writes are refused until {@link ProfileRepositoryMigration} copied them, so no profile
     * is created over data that was not migrated yet.
     *
     * @throws IOException when Redis cannot be read
     * @throws IllegalStateException when this Redis or repository is refused
     */
    public static RedisProfileRepository open(final RedisFabric fabric, final ProfileSettings.Repository settings,
                                              final Path fileDirectory) throws IOException {
        return open(fabric, fabric.sessions(), settings, fileDirectory);
    }

    /**
     * {@link #open(RedisFabric, ProfileSettings.Repository, Path)} with the session fabric whose claims this
     * repository's writes present: every format-1 write rebuilds its claim's {@code node/incarnation/owner} from
     * {@code sessions} ({@link #claimSession}), so it must be the fabric the process's ownership claims with. A process
     * has one, {@code fabric.sessions()}; only a test that runs two nodes on one fabric
     * ({@code FabricTestNodes#sessionsAs}) hands each node its own.
     */
    static RedisProfileRepository open(final RedisFabric fabric, final SessionFabric sessions,
                                       final ProfileSettings.Repository settings, final Path fileDirectory) throws IOException {
        java.util.Objects.requireNonNull(sessions, "sessions");
        final Durability durability = Durability.probe(fabric.client(RedisWorkload.CONTROL));
        durability.requireSupported(settings.requireAof());
        if (!durability.aof()) {
            SourbyLogger.warn("AWF MultiProfile: Redis has appendonly off and redis.require-aof is false; profiles are"
                + " as durable as Redis's RDB snapshots, and a Redis restart loses everything written since the last one"
                + (settings.waitForAof() ? ". redis.wait-for-aof is true, so every format-2 write stays UNKNOWN to its caller"
                    + " (WAITAOF cannot confirm it); set wait-for-aof: false to acknowledge writes from memory" : ""));
        }
        if (durability.replicas() > 0) {
            SourbyLogger.warn("AWF MultiProfile: Redis has " + durability.replicas() + " replica(s). Replication is"
                + " asynchronous: a failover to a replica can lose acknowledged profile writes and move session epochs"
                + " back. One primary is the supported topology for repository: redis");
        }
        final RedisProfileRepository repository = new RedisProfileRepository(fabric.storageClient(), sessions,
            fabric.prefix() + "mp:", durability, settings.waitForAof());
        repository.probeWaitAof();
        repository.checkFormat();
        repository.checkUnmigrated(fileDirectory.toAbsolutePath().normalize());
        return repository;
    }

    /**
     * Probes {@code WAITAOF} once when {@code wait-for-aof} is configured: Redis before 7.2 has no such command
     * and Redis with appendonly off refuses it. A format-2 store (or a migration to one, {@link #writeStoreFormat})
     * cannot run on such a Redis with the confirmation on, because every COMMITTED write would be reported UNKNOWN
     * to its caller (every login and save would fail), so opening a format-2 store is refused here with the key to
     * change; on a format-1 store the Build 50 behaviour stays (one WARN, confirmation off for its writes).
     */
    private void probeWaitAof() throws IOException {
        if (!this.waitForAofConfigured) return;
        String why = null;
        final int major = this.durability.majorVersion();
        final int minor = this.durability.minorVersion();
        if (major < 7 || (major == 7 && minor < 2)) {
            why = "Redis " + this.durability.version() + " has no WAITAOF (Redis 7.2+)";
        } else if (!this.durability.aof()) {
            why = "Redis has appendonly off, so WAITAOF cannot confirm anything";
        } else {
            final List<Object> replies = this.client.pipeline(List.of(new Object[] {"HINCRBY", meta(), "aof-probe", "1"},
                new Object[] {"WAITAOF", "1", "0", Integer.toString(Math.max(1, this.client.remainingTimeoutMillis() / 2))}));
            if (replies.size() > 1 && replies.get(1) instanceof RedisClient.RedisError refused) {
                why = "Redis refuses WAITAOF (" + refused.getMessage() + ")";
            }
        }
        this.waitAofUnsupported = why;
        if (why == null) return;
        final StoreFormat.Marker marker = storeFormat();
        if (!marker.legacyFormat()) {
            throw new IllegalStateException("aurora.awf.multiprofile.redis.wait-for-aof is true but " + why + "; a format-2 profile"
                + " store cannot run with a confirmation Redis cannot give (every write would be UNKNOWN to its caller). Enable"
                + " appendonly on Redis 7.2+, or set aurora.awf.multiprofile.redis.wait-for-aof: false to acknowledge writes from"
                + " Redis's memory");
        }
        this.waitForAof = false;
        SourbyLogger.warn("AWF MultiProfile: " + why + "; format-1 profile writes are acknowledged from Redis's memory (as in"
            + " Build 50). A format-2 migration of this store is refused until appendonly is on (Redis 7.2+) or"
            + " aurora.awf.multiprofile.redis.wait-for-aof is false");
    }

    // ------------------------------------------------------------------ status

    @Override
    public String describe() {
        final Durability durability = this.durability;
        final long unconfirmed = this.unconfirmed.get();
        final String refused = this.writesRefused;
        final StoreFormat.Marker marker = this.marker;
        return "redis " + this.namespace + "*, Redis " + durability.version()
            + (durability.replicas() > 0 ? " with " + durability.replicas() + " async replica(s)" : "")
            + ", " + (durability.aof() ? "AOF on (appendfsync " + durability.appendfsync() + ")" : "AOF OFF")
            + ", WAITAOF " + (this.waitForAof ? "on" : "off")
            + ", store format " + marker.format() + (marker.legacyFormat() ? "" : " (" + marker.migration() + ", schema " + marker.schema() + ")")
            + (unconfirmed > 0 ? ", " + unconfirmed + " write(s) not confirmed on disk in time" : "")
            + (refused == null ? "" : "; WRITES REFUSED: " + refused);
    }

    /** A failed write may have run in Redis with its reply lost; see {@link ProfileRepository#remote()}. */
    @Override
    public boolean remote() {
        return true;
    }

    // ------------------------------------------------------------------ reads (both formats)

    @Override
    public PlayerAccount loadAccount(final UUID playerId, final int defaultSlots) throws IOException {
        final String key = accountKey(playerId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_ACCOUNT);
        if (frame == null) return PlayerAccount.empty(playerId, defaultSlots);
        return decodeAccount(frame, playerId, key);
    }

    @Override
    public Optional<AuroraProfile> profile(final UUID profileId) throws IOException {
        final String key = profileKey(profileId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_PROFILE);
        return frame == null ? Optional.empty() : Optional.of(decodeProfile(frame, profileId, key));
    }

    @Override
    public List<AuroraProfile> profiles(final UUID accountId) throws IOException {
        final PlayerAccount account = loadAccount(accountId, PlayerAccount.DEFAULT_SLOTS);
        if (account.profileIds().isEmpty()) return List.of();
        final List<Object[]> commands = new ArrayList<>(account.profileIds().size());
        for (final UUID id : account.profileIds()) commands.add(new Object[] {"HMGET", profileKey(id), "rev", "rec"});
        ensureOpen();
        final List<Object> replies = this.client.pipelineBounded(bound(ProfileRecords.TYPE_PROFILE), commands);
        final List<AuroraProfile> found = new ArrayList<>(replies.size());
        for (int i = 0; i < replies.size(); i++) {
            final UUID id = account.profileIds().get(i);
            final String key = profileKey(id);
            final ProfileRecords.Frame frame = frameOf(key, ProfileRecords.TYPE_PROFILE, replies.get(i));
            if (frame != null) found.add(decodeProfile(frame, id, key));
        }
        return List.copyOf(found);
    }

    @Override
    public Optional<ProfileWorldBinding> binding(final UUID profileId) throws IOException {
        final String key = bindingKey(profileId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_BINDING);
        if (frame == null) return Optional.empty();
        checkFrameFormat(frame, key);
        return Optional.of(frame.format() == ProfileRecords.FORMAT ? ProfileRecords.binding(frame, key)
            : ProfileRecords.binding2(frame, profileId, key).binding());
    }

    /**
     * The format-1 snapshot the profile record names as its latest, which the publish script sets in the
     * step that advances the revision. A record that names a snapshot which is gone fails instead of
     * falling back to an older one. Refused in a format-2 store: there the state lives in member streams
     * ({@link #memberSnapshot}).
     */
    @Override
    public Optional<ProfileSnapshot> latestSnapshot(final UUID profileId) throws IOException {
        ensureOpen();
        requireLegacySnapshots("latestSnapshot");
        String id = RedisClient.text(this.client.call("HGET", profileKey(profileId), "latest"));
        if (id == null) {
            final List<?> newest = list(this.client.call("ZREVRANGE", snapIndexKey(profileId), "0", "0"));
            if (newest.isEmpty()) return Optional.empty();
            id = RedisClient.text(newest.getFirst());
        }
        final UUID snapshotId = uuid(id, profileKey(profileId) + " latest");
        final Optional<ProfileSnapshot> snapshot = snapshotById(profileId, snapshotId);
        if (snapshot.isEmpty()) {
            throw new IOException("snapshot " + id + " named by " + profileKey(profileId) + " is missing from "
                + snapsKey(profileId));
        }
        return snapshot;
    }

    @Override
    public Optional<ProfileSnapshot> snapshotById(final UUID profileId, final UUID snapshotId) throws IOException {
        ensureOpen();
        requireLegacySnapshots("snapshotById");
        final String where = snapsKey(profileId) + " " + snapshotId;
        final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_SNAPSHOT), "HGET", snapsKey(profileId),
            snapshotId.toString());
        if (!(stored instanceof byte[] bytes)) return Optional.empty();
        final ProfileSnapshot snapshot = ProfileRecords.snapshot(
            ProfileRecords.unframe(bytes, ProfileRecords.FORMAT, ProfileRecords.TYPE_SNAPSHOT, where), profileId, where);
        if (!snapshot.snapshotId().equals(snapshotId)) throw new IOException("snapshot " + where + " holds " + snapshot.snapshotId());
        return Optional.of(snapshot);
    }

    /** Every format-1 snapshot of a profile, oldest revision first; for backups and the offline migration. */
    public List<ProfileSnapshot> snapshots(final UUID profileId) throws IOException {
        ensureOpen();
        requireLegacySnapshots("snapshots");
        final List<?> ids = list(this.client.call("ZRANGE", snapIndexKey(profileId), "0", "-1"));
        final List<ProfileSnapshot> found = new ArrayList<>(ids.size());
        for (final Object id : ids) {
            final UUID snapshotId = uuid(RedisClient.text(id), snapIndexKey(profileId));
            final Optional<ProfileSnapshot> snapshot = snapshotById(profileId, snapshotId);
            if (snapshot.isEmpty()) throw new IOException("snapshot " + snapshotId + " indexed by " + snapIndexKey(profileId) + " is missing");
            found.add(snapshot.get());
        }
        return List.copyOf(found);
    }

    // ------------------------------------------------------------------ session markers (Build 53)

    @Override
    public boolean supportsSessionMarkers() {
        return true;
    }

    /** {@code <namespace>session:<player>}: the marker's frame as a plain string value; Build 52 never reads the key. */
    @Override
    public Optional<SessionMarker> sessionMarker(final UUID playerId) throws IOException {
        ensureOpen();
        final String key = sessionMarkerKey(playerId);
        final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_SESSION_MARKER), "GET", key);
        if (stored == null) return Optional.empty();
        if (!(stored instanceof byte[] bytes)) throw new IOException("the session marker " + key + " is not a bulk string");
        final ProfileRecords.Frame frame = ProfileRecords.unframe(bytes, ProfileRecords.FORMAT_2, ProfileRecords.TYPE_SESSION_MARKER, key);
        return Optional.of(SessionMarker.decode(frame.payload(), playerId, key));
    }

    @Override
    public boolean writeSessionMarker(final SessionMarker marker) throws IOException {
        ensureWritable();
        final String key = sessionMarkerKey(marker.playerId());
        final byte[] frame = ProfileRecords.frame2(ProfileRecords.TYPE_SESSION_MARKER, marker.generation(), marker.encode(), key);
        final Object reply = this.client.call("SET", key, frame);
        if (!"OK".equals(RedisClient.text(reply))) throw new IOException("writing the session marker " + key + " answered " + reply);
        return true;
    }

    @Override
    public Optional<ProfileSnapshot> legacySnapshot(final UUID playerId) throws IOException {
        ensureOpen();
        final String key = legacyKey(playerId);
        final String id = RedisClient.text(this.client.call("HGET", key, "latest"));
        if (id == null) return Optional.empty();
        final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_SNAPSHOT), "HGET", key, id);
        if (!(stored instanceof byte[] bytes)) throw new IOException("the archive " + id + " named by " + key + " is missing");
        return Optional.of(decodeLegacy(bytes, playerId, key + " " + id));
    }

    /** Every pre-profile archive of a player, newest last; for backups and the offline migration. */
    public List<ProfileSnapshot> legacySnapshots(final UUID playerId) throws IOException {
        ensureOpen();
        final String key = legacyKey(playerId);
        final List<?> fields = list(this.client.call("HKEYS", key));
        if (fields.size() > 1024) throw new IOException(key + " holds " + fields.size() + " archives; inspect it");
        final List<ProfileSnapshot> found = new ArrayList<>();
        String latest = null;
        for (final Object field : fields) {
            final String name = RedisClient.text(field);
            if ("latest".equals(name)) {
                latest = RedisClient.text(this.client.call("HGET", key, "latest"));
                continue;
            }
            final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_SNAPSHOT), "HGET", key, name);
            if (stored instanceof byte[] bytes) found.add(decodeLegacy(bytes, playerId, key + " " + name));
        }
        found.sort(Comparator.comparingLong(ProfileSnapshot::createdAtMillis));
        if (latest != null) {
            final UUID latestId = uuid(latest, key + " latest");
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).snapshotId().equals(latestId) && i != found.size() - 1) {
                    found.add(found.remove(i));
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    @Override
    public Optional<ProfileTransition> transition(final UUID transitionId) throws IOException {
        ensureOpen();
        final String player = RedisClient.text(this.client.call("HGET", journalPlayerKey(), transitionId.toString()));
        if (player == null) return Optional.empty();
        final String key = journalsKey(uuid(player, journalPlayerKey() + " " + transitionId));
        final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_TRANSITION), "HGET", key, transitionId.toString());
        if (!(stored instanceof byte[] bytes)) return Optional.empty();
        return Optional.of(journal(key, transitionId.toString(), bytes));
    }

    /**
     * The journals of one player, oldest first. One that cannot be read fails the call naming it and how
     * to remove it: that player's journal state is unknown, other players are unaffected.
     */
    @Override
    public List<ProfileTransition> transitionsFor(final UUID playerId) throws IOException {
        ensureOpen();
        final String key = journalsKey(playerId);
        final long count = number(this.client.call("HLEN", key));
        if (count > MAX_JOURNALS_PER_PLAYER) {
            throw new IOException(key + " holds " + count + " transition journals, more than " + MAX_JOURNALS_PER_PLAYER
                + " that a player can have; inspect them before this player can switch or save");
        }
        final List<?> flat = list(this.client.callBounded(bound(ProfileRecords.TYPE_TRANSITION), "HGETALL", key));
        final List<ProfileTransition> found = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            final String transitionId = RedisClient.text(flat.get(i));
            if (!(flat.get(i + 1) instanceof byte[] bytes)) continue;
            try {
                found.add(journal(key, transitionId, bytes));
            } catch (final IOException failed) {
                throw new IOException("the transition journal " + transitionId + " of " + playerId + " cannot be read ("
                    + failed.getMessage() + "); inspect it, then remove it to unblock this player: HDEL " + key + " "
                    + transitionId + ", HDEL " + journalMetaKey(playerId) + " " + transitionId + ", HDEL "
                    + journalPlayerKey() + " " + transitionId + ", SREM " + pendingKey() + " " + transitionId, failed);
            }
        }
        found.sort(Comparator.comparingLong(ProfileTransition::updatedAtMillis));
        return List.copyOf(found);
    }

    /**
     * For status output: the pending journals (up to {@code MAX_LISTED_PENDING}) and the most recent
     * failed ones ({@code FAILED_LISTED}), oldest first. Unreadable ones are skipped and reported by
     * {@link #quarantinedJournals()}.
     */
    @Override
    public List<ProfileTransition> transitions() throws IOException {
        ensureOpen();
        final Set<String> ids = new LinkedHashSet<>();
        String cursor = "0";
        do {
            final List<?> page = list(this.client.call("SSCAN", pendingKey(), cursor, "COUNT", "256"));
            if (page.size() < 2) throw new IOException("SSCAN of " + pendingKey() + " returned a malformed reply");
            cursor = RedisClient.text(page.get(0));
            for (final Object member : list(page.get(1))) {
                if (ids.size() < MAX_LISTED_PENDING) ids.add(RedisClient.text(member));
            }
        } while (!"0".equals(cursor) && ids.size() < MAX_LISTED_PENDING);
        for (final Object member : list(this.client.call("ZREVRANGE", failedKey(), "0", Integer.toString(FAILED_LISTED - 1)))) {
            ids.add(RedisClient.text(member));
        }
        final List<ProfileTransition> found = new ArrayList<>(ids.size());
        final List<String> failed = new ArrayList<>();
        for (final String id : ids) {
            try {
                transition(uuid(id, pendingKey())).ifPresent(found::add);
            } catch (final IOException broken) {
                failed.add(id + " (" + broken.getMessage() + ")");
            }
        }
        this.unreadable = List.copyOf(failed);
        found.sort(Comparator.comparingLong(ProfileTransition::updatedAtMillis));
        return List.copyOf(found);
    }

    @Override
    public List<String> quarantinedJournals() {
        return this.unreadable;
    }

    /** Every account id stored (bounded {@code SCAN}); for backups and the offline migration. */
    public List<UUID> accountIds() throws IOException {
        return scanIds("account:");
    }

    /** Every profile id stored (bounded {@code SCAN}); for backups and the offline migration. */
    public List<UUID> profileIds() throws IOException {
        return scanIds("profile:");
    }

    private List<UUID> scanIds(final String kind) throws IOException {
        ensureOpen();
        final String prefix = this.namespace + kind;
        final Set<UUID> ids = new LinkedHashSet<>();
        String cursor = "0";
        do {
            final List<?> page = list(this.client.call("SCAN", cursor, "MATCH", prefix + "*", "COUNT", "1000"));
            if (page.size() < 2) throw new IOException("SCAN of " + prefix + "* returned a malformed reply");
            cursor = RedisClient.text(page.get(0));
            for (final Object key : list(page.get(1))) {
                final String text = RedisClient.text(key);
                if (text == null || !text.startsWith(prefix)) continue;
                ids.add(uuid(text.substring(prefix.length()), text));
                if (ids.size() > MAX_ENUMERATED) throw new IOException("more than " + MAX_ENUMERATED + " " + kind + " keys; refusing to enumerate");
            }
        } while (!"0".equals(cursor));
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------ format-1 writes

    @Override
    public void saveAccount(final PlayerAccount account, final ProfileWriteGuard guard) throws IOException {
        requireLegacyWrite("saveAccount");
        final String key = accountKey(account.playerId());
        commit("account " + account.playerId(), SAVE_RECORD, guard, Ack.AOF, List.of(key), List.of(expected(guard), "0",
            Long.toString(account.revision()),
            ProfileRecords.frame(ProfileRecords.TYPE_ACCOUNT, account.revision(), ProfileRecords.accountPayload(account), key)));
    }

    @Override
    public void saveProfile(final AuroraProfile profile, final ProfileWriteGuard guard) throws IOException {
        requireLegacyWrite("saveProfile");
        final String key = profileKey(profile.id());
        commit("profile " + profile.id(), SAVE_RECORD, guard, Ack.AOF, List.of(key), List.of(expected(guard),
            Long.toString(ProfileWriteGuard.ABSENT), Long.toString(profile.revision()), profileRecord(profile)));
    }

    /** Bindings carry no revision; the guard's claim is checked, its revision is not (as in the file repository). */
    @Override
    public void saveBinding(final ProfileWorldBinding binding, final ProfileWriteGuard guard) throws IOException {
        requireLegacyWrite("saveBinding");
        final String key = bindingKey(binding.profileId());
        commit("binding of " + binding.profileId(), SAVE_RECORD, guard, Ack.AOF, List.of(key), List.of("any", "-1", "1",
            ProfileRecords.frame(ProfileRecords.TYPE_BINDING, 1L, ProfileRecords.bindingPayload(binding), key)));
    }

    @Override
    public void publishSnapshot(final AuroraProfile next, final ProfileSnapshot snapshot, final int retain,
                                final ProfileWriteGuard guard) throws IOException {
        if (!next.id().equals(snapshot.profileId())) {
            throw new IllegalArgumentException("snapshot of " + snapshot.profileId() + " published for " + next.id());
        }
        requireLegacyWrite("publishSnapshot");
        ensureWritable();
        final String stage = stageKey(snapshot.snapshotId());
        stage(stage, ProfileRecords.frame(ProfileRecords.TYPE_SNAPSHOT, snapshot.revision(), ProfileRecords.snapshotPayload(snapshot), stage));
        try {
            commit("profile " + next.id(), PUBLISH_SNAPSHOT, guard, Ack.AOF,
                List.of(profileKey(next.id()), stage, snapsKey(next.id()), snapIndexKey(next.id()),
                    journalMetaKey(next.accountId())),
                List.of(expected(guard), Long.toString(next.revision()), profileRecord(next),
                    snapshot.snapshotId().toString(), Long.toString(snapshot.revision()),
                    Integer.toString(Math.max(1, retain))));
        } catch (final IOException | RuntimeException failed) {
            // Refused, or the reply was lost: dropping the staged record also stops a commit that is
            // still on its way from publishing it (the script then finds nothing to move).
            discard(stage);
            throw failed;
        }
    }

    /**
     * Archives the pre-profile state. In a format-1 store the frame is format 1; in a format-2 store (or
     * during its migration) it is a format-2 legacy archive, fenced by the guard's claim, which may be a
     * session or a co-op claim.
     */
    @Override
    public void saveLegacySnapshot(final UUID playerId, final ProfileSnapshot snapshot, final ProfileWriteGuard guard)
        throws IOException {
        if (!snapshot.profileId().equals(playerId)) {
            throw new IllegalArgumentException("legacy snapshot of " + snapshot.profileId() + " saved for " + playerId);
        }
        ensureWritable();
        final String stage = stageKey(snapshot.snapshotId());
        final String format = encodingFormat();
        final byte[] record = FORMAT.equals(format)
            ? ProfileRecords.frame(ProfileRecords.TYPE_SNAPSHOT, snapshot.revision(), ProfileRecords.snapshotPayload(snapshot), stage)
            : ProfileRecords.frame2(ProfileRecords.TYPE_SNAPSHOT, snapshot.revision(), ProfileRecords.legacyArchivePayload2(snapshot), stage);
        stage(stage, record);
        try {
            commit("the pre-profile archive of " + playerId, SAVE_LEGACY, guard, Ack.AOF, List.of(legacyKey(playerId), stage),
                List.of(snapshot.snapshotId().toString(), format));
        } catch (final IOException | RuntimeException failed) {
            discard(stage);
            throw failed;
        }
    }

    /**
     * Writes a journal. The phase written before a target state is applied waits for the append-only
     * file (with {@code wait-for-aof}): recovery must find it after a Redis crash, or it would treat an
     * applied target as never applied. Other phases are acknowledged from memory. In a format-2 store the
     * journal is a format-2 frame and must carry its co-op fields.
     */
    @Override
    public void saveTransition(final ProfileTransition transition, final ProfileWriteGuard guard) throws IOException {
        final UUID playerId = transition.playerId();
        final String id = transition.transitionId().toString();
        final String key = journalsKey(playerId);
        final byte[] record;
        final String format = encodingFormat();
        if (FORMAT.equals(format)) {
            record = ProfileRecords.frame(ProfileRecords.TYPE_TRANSITION, 1L, ProfileRecords.transitionPayload(transition), key + " " + id);
        } else {
            if (transition.coop() == null) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "transition journal " + id
                    + " carries no co-op fields; a format-2 store refuses it and nothing was written");
            }
            record = ProfileRecords.frame2(ProfileRecords.TYPE_TRANSITION, 1L, ProfileRecords.transitionPayload2(transition), key + " " + id);
        }
        commit("transition journal " + id, SAVE_JOURNAL, guard,
            transition.phase() == TransitionPhase.APPLYING_TARGET ? Ack.AOF : Ack.MEMORY,
            List.of(key, journalMetaKey(playerId), journalPlayerKey(), pendingKey(), failedKey()),
            List.of(id, playerId.toString(), record,
                transition.terminal() ? "1" : "0", Long.toString(transition.updatedAtMillis()),
                idOrDash(transition.fromSnapshotId()), idOrDash(transition.toSnapshotId()),
                transition.phase() == TransitionPhase.FAILED ? "1" : "0",
                Integer.toString(FileProfileRepository.TERMINAL_JOURNALS_KEPT), Integer.toString(MAX_JOURNALS_PER_PLAYER),
                Integer.toString(FAILED_LISTED), format));
    }

    /**
     * The store format a journal or archive is encoded for, from this node's cached marker; the write
     * script compares it with the marker atomically and refuses a stale node (and any migration in
     * progress), so a format-1 frame is never written into a format-2 store or the reverse.
     */
    private String encodingFormat() {
        final StoreFormat.Marker marker = this.marker;
        if (marker.migration() == StoreFormat.MigrationState.IN_PROGRESS) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "a format-2 migration of the Redis store is in"
                + " progress; journal and archive writes are refused and nothing was written");
        }
        return marker.legacyFormat() ? FORMAT : Integer.toString(marker.format());
    }

    @Override
    public void deleteTransition(final UUID transitionId, final ProfileWriteGuard guard) throws IOException {
        ensureWritable();
        final String player = RedisClient.text(this.client.call("HGET", journalPlayerKey(), transitionId.toString()));
        if (player == null) return;
        final UUID playerId = uuid(player, journalPlayerKey() + " " + transitionId);
        commit("transition journal " + transitionId, DELETE_JOURNAL, guard, Ack.MEMORY,
            List.of(journalsKey(playerId), journalMetaKey(playerId), journalPlayerKey(), pendingKey(), failedKey()),
            List.of(transitionId.toString(), player, encodingFormat()));
    }

    @Override
    public void deleteProfileData(final UUID profileId, final ProfileWriteGuard guard) throws IOException {
        requireLegacyWrite("deleteProfileData");
        commit("the data of profile " + profileId, DELETE_PROFILE_DATA, guard, Ack.MEMORY,
            List.of(bindingKey(profileId), snapsKey(profileId), snapIndexKey(profileId), profileKey(profileId)), List.of());
    }

    /** Stops accepting calls; the fabric owns the connection pool and closes it. */
    @Override
    public void close() {
        this.closed = true;
    }

    // ================================================================== store format (OD-2)

    @Override
    public StoreFormat.Marker storeFormat() throws IOException {
        ensureOpen();
        final List<?> fields = list(this.client.call("HMGET", meta(), StoreFormat.REDIS_FORMAT_FIELD, StoreFormat.REDIS_MIGRATION_FIELD,
            StoreFormat.REDIS_SCHEMA_FIELD, "writtenAt", StoreFormat.REDIS_WRITTEN_BY_FIELD, StoreFormat.REDIS_MINIMUM_BUILD_FIELD));
        final String format = fields.isEmpty() ? null : RedisClient.text(fields.get(0));
        final StoreFormat.Marker marker;
        if (format == null || FORMAT.equals(format)) {
            marker = StoreFormat.Marker.legacy();
        } else {
            final String where = meta() + " " + StoreFormat.REDIS_FORMAT_FIELD;
            try {
                final int formatNumber = Integer.parseInt(format);
                final String state = fields.size() > 1 ? RedisClient.text(fields.get(1)) : null;
                final StoreFormat.MigrationState migration = state == null ? StoreFormat.MigrationState.NONE
                    : StoreFormat.MigrationState.fromTag(Integer.parseInt(state), where);
                final String schema = fields.size() > 2 ? RedisClient.text(fields.get(2)) : null;
                final String writtenAt = fields.size() > 3 ? RedisClient.text(fields.get(3)) : null;
                final String writtenBy = fields.size() > 4 ? RedisClient.text(fields.get(4)) : null;
                final String minimumBuild = fields.size() > 5 ? RedisClient.text(fields.get(5)) : null;
                marker = new StoreFormat.Marker(formatNumber, migration, schema == null ? 0 : Integer.parseInt(schema),
                    writtenAt == null ? 0L : Long.parseLong(writtenAt), writtenBy == null ? "" : writtenBy,
                    minimumBuild == null ? 0 : Integer.parseInt(minimumBuild));
            } catch (final IllegalArgumentException malformed) {
                throw new IOException("the store format marker in " + meta() + " is malformed: " + malformed.getMessage());
            }
        }
        this.marker = marker;
        return marker;
    }

    /**
     * Writes the marker. Redis has no migration lock of its own: the caller runs this offline with every
     * writer stopped, as {@link ProfileRepository#writeStoreFormat} requires.
     */
    @Override
    public void writeStoreFormat(final StoreFormat.Marker marker) throws IOException {
        ensureOpen();
        if (this.waitForAofConfigured && this.waitAofUnsupported != null) {
            // Before any marker: a store marked IN_PROGRESS or READY on this Redis could not be written afterwards.
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "the format-2 marker is not written:"
                + " aurora.awf.multiprofile.redis.wait-for-aof is true but " + this.waitAofUnsupported + ", so every format-2 write"
                + " would be UNKNOWN to its caller. Enable appendonly on Redis 7.2+, or set wait-for-aof: false, then run the"
                + " migration again; nothing was written");
        }
        if (marker.format() != StoreFormat.CURRENT || marker.schema() != StoreFormat.SCHEMA) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "this build writes only format-" + StoreFormat.CURRENT
                + " schema-" + StoreFormat.SCHEMA + " markers; a downgrade marker is never written");
        }
        final StoreFormat.Marker current = storeFormat();
        if (marker.migration() == StoreFormat.MigrationState.IN_PROGRESS && !current.legacyFormat()
            && current.migration() != StoreFormat.MigrationState.IN_PROGRESS) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the store is already format 2; a migration marker"
                + " is not written over it");
        }
        if (!current.legacyFormat() && (current.schema() > marker.schema() || current.minimumBuild() > marker.minimumBuild())) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "the store was raised to schema " + current.schema()
                + " / Build " + current.minimumBuild() + "; a marker below that (schema " + marker.schema() + ", Build "
                + marker.minimumBuild() + ") is never written. Nothing was written");
        }
        final Object reply = this.client.eval(RedisProfileScripts.WRITE_FORMAT, 1, meta(), Integer.toString(marker.format()),
            Integer.toString(marker.migration().tag()), Integer.toString(marker.schema()), Long.toString(marker.writtenAtMillis()),
            marker.writtenBy(), Integer.toString(StoreFormat.CURRENT));
        final List<?> fields = list(reply);
        final String status = fields.isEmpty() ? null : RedisClient.text(fields.get(0));
        if ("FORMAT".equals(status)) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, fields.size() > 1 ? RedisClient.text(fields.get(1)) : "refused");
        }
        if (!"OK".equals(status)) throw new IOException("writing the store format marker returned " + status);
        if (this.waitForAof) {
            final int waitMillis = Math.max(1, this.client.remainingTimeoutMillis() / 2);
            if (!aofConfirmed(waitMillis)) {
                throw new IOException("the store format marker was written but Redis did not confirm it in its append-only"
                    + " file within " + waitMillis + " ms; re-run the migration step after checking Redis");
            }
        }
        this.marker = marker;
    }

    /**
     * {@link ProfileRepository#requireStoreFeature}: one script raises {@code schema} and {@code minimumBuild} in the
     * {@code meta} hash (never lowering them) after checking the store is format 2 and not migrating; with
     * {@code wait-for-aof} the change must be confirmed in the append-only file before this returns. Every write
     * script reads the same hash, so a Build 51 node still running against this Redis refuses its next write.
     */
    @Override
    public StoreFormat.Marker requireStoreFeature(final int schema, final int minimumBuild, final String writtenBy) throws IOException {
        ensureOpen();
        if (schema > StoreFormat.MAX_SCHEMA || minimumBuild > StoreFormat.READS_UP_TO_BUILD) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "this build raises a store to at most schema "
                + StoreFormat.MAX_SCHEMA + " and Build " + StoreFormat.READS_UP_TO_BUILD);
        }
        final StoreFormat.Marker current = storeFormat();
        if (current.format() == StoreFormat.CURRENT && current.migration() != StoreFormat.MigrationState.IN_PROGRESS
            && current.covers(schema, minimumBuild)) {
            return current;
        }
        final String writer = writtenBy == null ? "" : writtenBy.length() <= 64 ? writtenBy : writtenBy.substring(0, 64);
        final Object reply = this.client.eval(RedisProfileScripts.RAISE_FORMAT, 1, meta(), Integer.toString(schema),
            Integer.toString(minimumBuild), Long.toString(System.currentTimeMillis()), writer, Integer.toString(StoreFormat.CURRENT));
        final List<?> fields = list(reply);
        final String status = fields.isEmpty() ? null : RedisClient.text(fields.get(0));
        if ("FORMAT".equals(status)) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, (fields.size() > 1 ? RedisClient.text(fields.get(1))
                : "refused") + "; no co-op record is written");
        }
        if (!"OK".equals(status)) throw new IOException("raising the store format marker returned " + status);
        if (this.waitForAof) {
            final int waitMillis = Math.max(1, this.client.remainingTimeoutMillis() / 2);
            if (!aofConfirmed(waitMillis)) {
                throw new IOException("the raised store format marker was written but Redis did not confirm it in its append-only"
                    + " file within " + waitMillis + " ms; no co-op record is written until it is confirmed");
            }
        }
        final StoreFormat.Marker raised = storeFormat();
        SourbyLogger.info("AWF MultiProfile: the profile store now requires Build " + raised.requiredBuild() + " or newer (schema "
            + raised.schema() + "); builds below it refuse it");
        return raised;
    }

    // ================================================================== format 2: guarded record writes

    @Override
    public CommitResult commitAccount(final PlayerAccount after, final CoopWriteGuard guard) throws IOException {
        final UUID playerId = after.playerId();
        final String what = "account " + playerId;
        requireActor(guard, playerId, what);
        final Expected expected = guard.expected(Domain.ACCOUNT, playerId, null, null);
        if (expected.revision() == CoopWriteGuard.ABSENT) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": accounts use revision 0 for an unstored"
                + " account, never ABSENT; nothing was written");
        }
        requireNext(expected.revision(), after.revision(), guard, what);
        final Set<String> keys = new java.util.HashSet<>();
        for (final String alias : after.aliases().values()) {
            if (!keys.add(CuteNames.key(alias))) {
                throw new ProfileOperationException(ProfileErrorCode.NAME_TAKEN, what + ": alias " + alias
                    + " is not unique in the account under lower-case comparison; nothing was written");
            }
        }
        // Co-op access and activation rules against the stored records; the script's A check (and the M entries a
        // switch guard carries) make the account read exact, so a record that moves in between fails the vector.
        FileProfileRepository.checkCoopAccess(loadAccount(playerId, this.defaultSlots), after, id -> profile(id).orElse(null), what);
        final String key = accountKey(playerId);
        final Call call = call(playerId, receiptFor(guard));
        call.check(expected, key);
        call.key(key);
        call.arg(Long.toString(after.revision()));
        call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_ACCOUNT, after.revision(), ProfileRecords.accountPayload2(after), key));
        call.arg(after.activeProfile() == null ? "" : after.activeProfile().toString());
        call.arg(after.pendingIntent() == null ? "" : after.pendingIntent().toString());
        call.arg(activeArg(guard.expectedActiveProfile()));
        call.arg(reservationsArg(guard));
        return run(what, RedisProfileScripts.COMMIT_ACCOUNT, guard, call, playerId);
    }

    @Override
    public CommitResult commitProfile(final AuroraProfile after, final CoopWriteGuard guard) throws IOException {
        final UUID profileId = after.id();
        final String what = "profile " + profileId;
        final Expected expected = guard.expected(Domain.METADATA, profileId, null, null);
        final boolean creation = expected.revision() == CoopWriteGuard.ABSENT;
        final boolean migration = guard.operation().kind() == CoopOperation.Kind.MIGRATION;
        if (after.legacy()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "a format-2 " + what + " has member rows; nothing was written");
        }
        if (creation) {
            // The new profile's owner account holds Q; a P claim, when carried, is on this profile.
            requireActor(guard, after.accountId(), what);
            if (guard.profile() != null) requireProfileClaim(guard, profileId, what);
            if (after.revision() != 0L || after.pendingIntent() != null) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "a new " + what + " starts at revision 0 with no"
                    + " reservation; nothing was written");
            }
        } else {
            requireProfileClaim(guard, profileId, what);
            requireActor(guard, null, what);
            if (!migration) {
                // As in FILE: only the profile's OWNER commits its metadata directly, and the owner account, name,
                // creation time and memberships are immutable here (membership changes are intents).
                final ProfileRecords.Frame frame = record(profileKey(profileId), ProfileRecords.TYPE_PROFILE);
                final AuroraProfile stored = frame == null ? null : ProfileRecords.profile2(frame, profileId, profileKey(profileId));
                final UUID actor = guard.actor().playerId();
                final ProfileMember row = stored == null ? null : stored.member(actor);
                if (row == null || row.role() != ProfileRole.OWNER) {
                    throw new ProfileOperationException(ProfileErrorCode.NOT_OWNED, actor + " is not the OWNER of the profile; " + what
                        + " was not written");
                }
                if (!stored.accountId().equals(after.accountId()) || !stored.name().equals(after.name())
                    || stored.createdAtMillis() != after.createdAtMillis()
                    || !FileProfileRepository.sameMemberships(stored.members(), after.members())) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "a direct commit of " + what + " cannot change"
                        + " its owner, name, creation time or memberships; membership changes are intents. Nothing was written");
                }
                if (stored.kind() != after.kind()) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "a direct commit of " + what + " cannot change"
                        + " its kind (" + stored.kind() + " to " + after.kind() + "); the conversion to co-op is an intent and one way."
                        + " Nothing was written");
                }
            }
        }
        if (!migration && ProfileRecords.needsProfileSchema2(after)) {
            final StoreFormat.Marker marker = storeFormat();
            if (!marker.covers(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD)) {
                throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, what + " is a co-op or labelled profile"
                    + " (payload schema 2) but the store marker is schema " + marker.schema() + ", minimum build " + marker.minimumBuild()
                    + "; raise the store's feature level first (requireStoreFeature). Nothing was written");
            }
        }
        final Expected account = guard.actor() == null ? null : guard.expected(Domain.ACCOUNT, guard.actor().playerId(), null, null);
        requireNext(expected.revision(), after.revision(), guard, what);
        final String key = profileKey(profileId);
        final Call call = call(profileId, receiptFor(guard));
        call.check(expected, key);
        if (account != null) call.check(account, accountKey(account.resource()));
        call.key(key);
        call.key(namesKey(after.accountId()));
        call.arg(Long.toString(after.revision()));
        call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_PROFILE, after.revision(), ProfileRecords.profilePayload2(after), key));
        call.arg(CuteNames.key(after.name()));
        call.arg(after.pendingIntent() == null ? "" : after.pendingIntent().toString());
        call.arg(profileId.toString());
        call.arg(after.status() == ProfileStatus.DELETED ? "1" : "0");
        call.arg(after.accountId().toString());
        call.arg(reservationsArg(guard));
        return run(what, RedisProfileScripts.COMMIT_PROFILE, guard, call, profileId);
    }

    @Override
    public OptionalLong bindingRevision(final UUID profileId) throws IOException {
        requireFormat2Read("bindingRevision");
        final String stored = RedisClient.text(this.client.call("HGET", bindingKey(profileId), "rev"));
        if (stored == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(CoopWriteGuard.counter(Long.parseLong(stored), "binding revision"));
        } catch (final IllegalArgumentException malformed) {
            throw new IOException(bindingKey(profileId) + " has revision field '" + stored + "'");
        }
    }

    @Override
    public CommitResult commitBinding(final ProfileWorldBinding binding, final long nextRevision, final CoopWriteGuard guard)
        throws IOException {
        final UUID profileId = binding.profileId();
        final String what = "the binding of " + profileId;
        requireProfileClaim(guard, profileId, what);
        requireActor(guard, null, what);
        // The offline migration converts the binding record alone (its vector names only the binding, as in FILE).
        final boolean migration = guard.operation().kind() == CoopOperation.Kind.MIGRATION;
        final Expected metadata = migration ? null : guard.expected(Domain.METADATA, profileId, null, null);
        final Expected record = guard.expected(Domain.RECORD, profileId, null, null);
        final Expected account = guard.actor() == null ? null : guard.expected(Domain.ACCOUNT, guard.actor().playerId(), null, null);
        requireNext(record.revision(), nextRevision, guard, what);
        final String key = bindingKey(profileId);
        // As in FILE: the actor must be the profile's OWNER, and a reservation the stored binding carries is kept
        // (the script refuses one outside the guard's reservations and any change to it).
        UUID pending = null;
        if (!migration) {
            final ProfileRecords.Frame profileFrame = record(profileKey(profileId), ProfileRecords.TYPE_PROFILE);
            if (profileFrame == null) {
                throw new ProfileOperationException(ProfileErrorCode.PROFILE_NOT_FOUND, what + ": no profile " + profileId);
            }
            final AuroraProfile profile = ProfileRecords.profile2(profileFrame, profileId, profileKey(profileId));
            final ProfileMember row = profile.member(guard.actor().playerId());
            if (row == null || row.role() != ProfileRole.OWNER) {
                throw new ProfileOperationException(ProfileErrorCode.NOT_OWNED, guard.actor().playerId() + " is not the OWNER of the"
                    + " profile; " + what + " was not written");
            }
            final ProfileRecords.Frame stored = record(key, ProfileRecords.TYPE_BINDING);
            if (stored != null && stored.format() == ProfileRecords.FORMAT_2) {
                pending = ProfileRecords.binding2(stored, profileId, key).pendingIntent();
            }
        }
        final Call call = call(profileId, receiptFor(guard));
        call.check(record, key);
        if (metadata != null) call.check(metadata, profileKey(profileId));
        if (account != null) call.check(account, accountKey(account.resource()));
        call.key(key);
        call.arg(Long.toString(nextRevision));
        call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_BINDING, nextRevision, ProfileRecords.bindingPayload2(binding, nextRevision, pending), key));
        call.arg(pending == null ? "" : pending.toString());
        call.arg(reservationsArg(guard));
        return run(what, RedisProfileScripts.COMMIT_RECORD, guard, call, profileId);
    }

    // ------------------------------------------------------------------ member streams

    @Override
    public Optional<MemberStateHead> memberHead(final UUID profileId, final UUID memberId, final UUID epoch) throws IOException {
        requireFormat2Read("memberHead");
        final String key = headKey(profileId, memberId, epoch);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_MEMBER_HEAD);
        if (frame == null) return Optional.empty();
        return Optional.of(ProfileRecords.memberHead2(frame, profileId, memberId, epoch, key));
    }

    /**
     * The stored head of every member row; a row whose stream never had a head record is skipped (as the
     * FILE repository does): a guard that publishes into such a stream expects {@link CoopWriteGuard#ABSENT},
     * and a synthetic revision-0 head would be mistaken for an expected revision.
     */
    @Override
    public List<MemberStateHead> memberHeads(final UUID profileId) throws IOException {
        requireFormat2Read("memberHeads");
        final Optional<AuroraProfile> profile = profile(profileId);
        if (profile.isEmpty()) return List.of();
        final List<ProfileMember> members = profile.get().members();
        if (members.isEmpty()) return List.of();
        final List<Object[]> commands = new ArrayList<>(members.size());
        for (final ProfileMember member : members) {
            commands.add(new Object[] {"HMGET", headKey(profileId, member.playerId(), member.epoch()), "rev", "rec"});
        }
        final List<Object> replies = this.client.pipelineBounded(bound(ProfileRecords.TYPE_MEMBER_HEAD), commands);
        final List<MemberStateHead> heads = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            final ProfileMember member = members.get(i);
            final String key = headKey(profileId, member.playerId(), member.epoch());
            final ProfileRecords.Frame frame = frameOf(key, ProfileRecords.TYPE_MEMBER_HEAD, replies.get(i));
            if (frame != null) heads.add(ProfileRecords.memberHead2(frame, profileId, member.playerId(), member.epoch(), key));
        }
        return List.copyOf(heads);
    }

    @Override
    public Optional<ProfileSnapshot> memberSnapshot(final UUID profileId, final UUID memberId, final UUID epoch, final UUID snapshotId)
        throws IOException {
        requireFormat2Read("memberSnapshot");
        final String key = memberSnapsKey(profileId, memberId, epoch);
        final String where = key + " " + snapshotId;
        final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_MEMBER_SNAPSHOT), "HGET", key, snapshotId.toString());
        if (!(stored instanceof byte[] bytes)) return Optional.empty();
        final ProfileSnapshot snapshot = ProfileRecords.memberSnapshot2(
            ProfileRecords.unframe(bytes, ProfileRecords.FORMAT_2, ProfileRecords.TYPE_MEMBER_SNAPSHOT, where), profileId, memberId,
            epoch, where);
        if (!snapshot.snapshotId().equals(snapshotId)) throw new IOException("member snapshot " + where + " holds " + snapshot.snapshotId());
        return Optional.of(snapshot);
    }

    @Override
    public CommitResult publishMemberSnapshot(final MemberStateHead next, final ProfileSnapshot snapshot, final int retain,
                                              final CoopWriteGuard guard) throws IOException {
        final UUID profileId = next.profileId();
        final UUID memberId = next.memberId();
        final UUID epoch = next.epoch();
        final String what = "member snapshot " + snapshot.snapshotId() + " of stream " + next.describe();
        if (!snapshot.inStream(profileId, memberId, epoch)) {
            throw new IllegalArgumentException("snapshot " + snapshot.snapshotId() + " is not in stream " + next.describe());
        }
        if (next.state() != MemberStateHead.State.READY || !snapshot.snapshotId().equals(next.snapshotId())
            || !snapshot.checksum().equals(next.snapshotChecksum()) || snapshot.revision() != next.revision()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the head must be READY and name exactly"
                + " this snapshot (id, revision and checksum); nothing was written");
        }
        requireActor(guard, memberId, what);
        final Expected head = guard.expected(Domain.HEAD, profileId, memberId, epoch);
        final Expected metadata = guard.expected(Domain.METADATA, profileId, null, null);
        final Expected account = guard.expected(Domain.ACCOUNT, memberId, null, null);
        // A stream without a head is the UNINITIALIZED head at revision 0 (as in FILE): its first publish is R = 1.
        requireNext(head.revision() == CoopWriteGuard.ABSENT ? 0L : head.revision(), next.revision(), guard, what);
        ensureWritable();
        final String headKey = headKey(profileId, memberId, epoch);
        final String stage = stageKey(snapshot.snapshotId());
        stage(stage, ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_SNAPSHOT, snapshot.revision(),
            ProfileRecords.memberSnapshotPayload2(snapshot), stage));
        try {
            final Call call = call(profileId, receiptFor(guard));
            call.check(head, headKey);
            call.check(metadata, profileKey(profileId));
            call.check(account, accountKey(memberId));
            call.key(headKey);
            call.key(stage);
            call.key(memberSnapsKey(profileId, memberId, epoch));
            call.key(memberSnapIndexKey(profileId, memberId, epoch));
            call.key(pinsKey(profileId, memberId, epoch));
            call.key(accountKey(memberId));
            call.arg(Long.toString(next.revision()));
            call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_HEAD, next.revision(), ProfileRecords.memberHeadPayload2(next), headKey));
            call.arg(snapshot.snapshotId().toString());
            call.arg(Integer.toString(Math.max(1, retain)));
            call.arg(next.pendingIntent() == null ? "" : next.pendingIntent().toString());
            call.arg(activeArg(guard.expectedActiveProfile()));
            call.arg(reservationsArg(guard));
            return run(what, RedisProfileScripts.PUBLISH_MEMBER, guard, call, profileId);
        } catch (final IOException | RuntimeException failed) {
            discard(stage);
            throw failed;
        }
    }

    /**
     * The pins of a stream, read with a bounded {@code HSCAN}; a pin hash with more than
     * {@value #MAX_PINS_PER_STREAM} entries or a malformed entry fails the call (pruning then stays off).
     */
    @Override
    public List<SnapshotRef> pinnedSnapshots(final UUID profileId, final UUID memberId, final UUID epoch) throws IOException {
        requireFormat2Read("pinnedSnapshots");
        final Map<UUID, Long> pins = readPins(profileId, memberId, epoch);
        final List<SnapshotRef> refs = new ArrayList<>(pins.size());
        for (final UUID id : pins.keySet()) refs.add(new SnapshotRef(profileId, memberId, epoch, id));
        return List.copyOf(refs);
    }

    private Map<UUID, Long> readPins(final UUID profileId, final UUID memberId, final UUID epoch) throws IOException {
        final String key = pinsKey(profileId, memberId, epoch);
        final Map<UUID, Long> pins = new LinkedHashMap<>();
        String cursor = "0";
        do {
            final List<?> page = list(this.client.call("HSCAN", key, cursor, "COUNT", "256"));
            if (page.size() < 2) throw new IOException("HSCAN of " + key + " returned a malformed reply");
            cursor = RedisClient.text(page.get(0));
            final List<?> flat = list(page.get(1));
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                final UUID id = uuid(RedisClient.text(flat.get(i)), key);
                final long count;
                try {
                    count = Long.parseLong(RedisClient.text(flat.get(i + 1)));
                } catch (final NumberFormatException malformed) {
                    throw new IOException("pin " + id + " in " + key + " has a malformed count; pruning of this stream is disabled");
                }
                if (count > 0) pins.put(id, count);
                if (pins.size() > MAX_PINS_PER_STREAM) {
                    throw new IOException(key + " holds more than " + MAX_PINS_PER_STREAM + " pins; pruning of this stream is disabled");
                }
            }
        } while (!"0".equals(cursor));
        return pins;
    }

    /**
     * Build 54: writes a member snapshot into its stream hash and revision index without touching the head, any revision
     * or receipt; the same snapshot twice is a no-op, another snapshot under the same id fails. A ready format-2 store is
     * required. The blob is named by the head after-image of a delivery intent and pinned by it; until that commits it is
     * an unreferenced entry the stream's pruning may remove.
     */
    @Override
    public void stageMemberSnapshot(final ProfileSnapshot snapshot) throws IOException {
        requireFormat2Read("stageMemberSnapshot");
        if (StoreFormat.gate(this.marker).gate() != StoreFormat.Gate.FORMAT_2) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "member snapshots are staged in a ready format-2"
                + " store only; nothing was written");
        }
        if (!snapshot.member()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "a staged member snapshot names member and epoch");
        }
        ensureWritable();
        final Optional<ProfileSnapshot> existing = memberSnapshot(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch(),
            snapshot.snapshotId());
        if (existing.isPresent()) {
            if (existing.get().revision() == snapshot.revision() && existing.get().checksum().equals(snapshot.checksum())) return;
            throw new IOException("member stream " + memberSnapsKey(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch())
                + " already holds snapshot " + snapshot.snapshotId() + " with other content");
        }
        final String snaps = memberSnapsKey(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch());
        final byte[] frame = ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_SNAPSHOT, snapshot.revision(),
            ProfileRecords.memberSnapshotPayload2(snapshot), snaps + " " + snapshot.snapshotId());
        final List<Object[]> commands = new ArrayList<>(2);
        commands.add(new Object[] {"HSET", snaps, snapshot.snapshotId().toString(), frame});
        commands.add(new Object[] {"ZADD", memberSnapIndexKey(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch()),
            Long.toString(snapshot.revision()), snapshot.snapshotId().toString()});
        this.client.pipeline(commands);
    }

    // ------------------------------------------------------------------ shared state

    @Override
    public Optional<ProfileSharedState> shared(final UUID profileId) throws IOException {
        requireFormat2Read("shared");
        final String key = sharedKey(profileId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_SHARED);
        return frame == null ? Optional.empty() : Optional.of(ProfileRecords.shared2(frame, profileId, key));
    }

    @Override
    public CommitResult mutateShared(final ProfileSharedState after, final CoopWriteGuard guard) throws IOException {
        final UUID profileId = after.profileId();
        final String what = "the shared state of " + profileId;
        requireProfileClaim(guard, profileId, what);
        requireActor(guard, null, what);
        final Expected shared = guard.expected(Domain.SHARED, profileId, null, null);
        final Expected metadata = guard.expected(Domain.METADATA, profileId, null, null);
        final Expected account = guard.actor() == null ? null : guard.expected(Domain.ACCOUNT, guard.actor().playerId(), null, null);
        requireNext(shared.revision(), after.revision(), guard, what);
        final String key = sharedKey(profileId);
        final Call call = call(profileId, receiptFor(guard));
        call.check(shared, key);
        call.check(metadata, profileKey(profileId));
        if (account != null) call.check(account, accountKey(account.resource()));
        call.key(key);
        call.key(guard.actor() == null ? meta() : accountKey(guard.actor().playerId()));
        call.arg(Long.toString(after.revision()));
        call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_SHARED, after.revision(), ProfileRecords.sharedPayload2(after), key));
        call.arg(after.pendingIntent() == null ? "" : after.pendingIntent().toString());
        // The actor must be active on the profile: the guard's predicate, or the profile itself when it names none.
        call.arg(guard.actor() == null ? "" : activeArg(guard.expectedActiveProfile() == null ? profileId : guard.expectedActiveProfile()));
        call.arg(reservationsArg(guard));
        return run(what, RedisProfileScripts.MUTATE_SHARED, guard, call, profileId);
    }

    // ------------------------------------------------------------------ intents

    /**
     * Stages one after-image payload for {@link #prepareIntent}: the reference is built from the payload,
     * the payload decoded as a record of that type (id, stream and revision checked) and written under a
     * content-addressed key with a TTL; staging the same bytes twice is idempotent. Prepare copies the
     * staged payload into the operation's durable blob hash; the operation id does not name the key, the
     * digest does. Member snapshots (type 10) are not after-images of an intent in Build 51: they are
     * published through {@link #publishMemberSnapshot} and pinned by the intent.
     */
    @Override
    public BlobRef stageAfterImage(final UUID operationId, final byte recordType, final UUID id, final UUID member, final UUID epoch,
                                   final long revision, final byte[] payload) throws IOException {
        requireFormat2Read("stageAfterImage");
        if (StoreFormat.gate(this.marker).gate() != StoreFormat.Gate.FORMAT_2) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "after-images are staged in a ready format-2"
                + " store only; nothing was written");
        }
        final BlobRef ref = new BlobRef(recordType, id, member, epoch, revision, payload.length, ProfileRecords.sha256(payload));
        stagePayload(ref, payload);
        return ref;
    }

    /**
     * Stages the <em>framed</em> record (format 2, type, revision, payload, digest), exactly the bytes
     * {@code COMMIT_INTENT} copies into the record hash: readers unframe every record, so a bare payload
     * installed as a record would be unreadable. The reference keeps describing the payload (length and
     * digest), as in the FILE backend.
     */
    private void stagePayload(final BlobRef ref, final byte[] payload) throws IOException {
        ensureWritable();
        if (payload.length != ref.length() || !MessageDigest.isEqual(ProfileRecords.sha256(payload), ref.sha256())) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the after-image of " + describe(ref)
                + " does not match its reference (length or digest); nothing was written");
        }
        final String key = blobStageKey(ref);
        final byte[] frame = ProfileRecords.frame2(ref.recordType(), ref.revision(), payload, key);
        // Unframing and decoding validate format, type, id, revision and bounds before anything is written.
        decodeAfterImage(ref, frame, key);
        final Object reply = this.client.call("SET", key, frame, "PX", Long.toString(STAGE_TTL_MILLIS), "NX");
        if (reply == null) {
            // Content-addressed: an existing key with the same digest holds the same bytes; refresh its TTL.
            this.client.call("PEXPIRE", key, Long.toString(STAGE_TTL_MILLIS));
        }
    }

    @Override
    public CommitResult prepareIntent(final ProfileIntent intent, final CoopWriteGuard guard) throws IOException {
        final UUID op = intent.operationId();
        final String what = "intent " + op + " (" + intent.kind() + ")";
        if (!op.equals(guard.operation().operationId()) || !intent.sameRequest(guard.operation().requestDigest())) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the guard belongs to another operation");
        }
        if (intent.phase() != ProfileIntent.Phase.PREPARED || intent.revision() != 0L) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": prepare takes a PREPARED intent at revision 0");
        }
        requireFormat2Read("prepareIntent");
        ensureWritable();
        // Record types of the after-images, to resolve RECORD-domain entries of the vector.
        final Map<UUID, Byte> hints = new HashMap<>();
        long blobBytes = 0L;
        for (final BlobRef ref : intent.afterImages()) {
            requireInstallable(ref, what);
            hints.put(ref.id(), ref.recordType());
            blobBytes += ref.length();
        }
        if (blobBytes > MAX_INTENT_BLOB_BYTES) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + " references " + blobBytes
                + " bytes of after-images, more than " + MAX_INTENT_BLOB_BYTES);
        }
        requireCoopStoreLevel(intent, what);
        final UUID resource = receiptResource(guard, intent);
        final Call call = call(resource, null);
        // The vector: the pre-reservation revision of every record (the script itself checks for an existing intent).
        final Map<Expected, String> targets = new LinkedHashMap<>();
        final Set<Expected> compared = new java.util.HashSet<>();
        for (final Expected after : intent.expectedAfter()) {
            final Expected before = matchingBefore(intent, after, guard, what);
            final String key = recordKey(before, op, hints, what);
            call.check(before, key);
            targets.put(before, key);
            if (compareOnly(before, after)) compared.add(before);
        }
        call.key(intentKey(op));
        call.key(blobsKey(op));
        call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, intent.revision(), ProfileRecords.intentPayload2(intent), intentKey(op)));
        call.arg(Long.toString(intent.revision()));
        call.arg(Integer.toString(intent.phase().tag()));
        call.arg(Integer.toString(intent.kind().tag()));
        call.arg(Long.toString(intent.createdAtMillis()));
        call.arg(resource.toString());
        call.arg(Integer.toString(intent.afterImages().size()));
        for (final BlobRef ref : intent.afterImages()) call.key(blobStageKey(ref));
        // The staged bytes are the framed record: payload length plus the frame overhead.
        for (final BlobRef ref : intent.afterImages()) call.arg(Integer.toString(ref.length() + ProfileRecords.OVERHEAD));
        // Reserved images: every existing record of expectedAfter re-encoded at revision + 1 with the reservation.
        final List<byte[]> reservedFrames = new ArrayList<>();
        final List<String> reservedKeys = new ArrayList<>();
        final List<Long> reservedRevisions = new ArrayList<>();
        for (final Map.Entry<Expected, String> target : targets.entrySet()) {
            final Expected before = target.getKey();
            if (before.revision() == CoopWriteGuard.ABSENT && before.domain() != Domain.ACCOUNT) continue;
            if (compared.contains(before)) continue;   // compared by the script's vector check, not reserved
            final Reserved reserved = reserve(before, target.getValue(), op, what);
            reservedKeys.add(target.getValue());
            reservedRevisions.add(reserved.revision());
            reservedFrames.add(reserved.frame());
        }
        call.arg(Integer.toString(reservedKeys.size()));
        for (final String key : reservedKeys) call.key(key);
        for (int i = 0; i < reservedKeys.size(); i++) {
            call.arg(Long.toString(reservedRevisions.get(i)));
            call.arg(reservedFrames.get(i));
        }
        // Compared records must not carry another operation's reservation (FILE refuses the same at prepare).
        appendCompared(call, targets, compared);
        call.arg(Integer.toString(intent.pins().size()));
        for (final SnapshotRef pin : intent.pins()) {
            if (pin.member() == null) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + " pins a format-1 snapshot " + pin.snapshotId()
                    + "; a format-2 store has member streams only");
            }
            call.key(pinsKey(pin.profileId(), pin.member(), pin.epoch()));
        }
        for (final SnapshotRef pin : intent.pins()) call.arg(pin.snapshotId().toString());
        final Map<String, Integer> indexes = intentIndexes(intent);
        call.arg(Integer.toString(indexes.size()));
        for (final String key : indexes.keySet()) call.key(key);
        final String member = indexMember(intent.createdAtMillis(), op);
        for (final int cap : indexes.values()) {
            call.arg(member);
            call.arg(Integer.toString(cap));
        }
        return run(what, RedisProfileScripts.PREPARE_INTENT, guard, call, resource);
    }

    /**
     * The store-side counterpart of the engine's feature-level raise: an intent whose after-images include a co-op
     * record (a schema-2 profile, an invite, vote or salvage record) is refused while the {@code meta} marker is below
     * schema {@link StoreFormat#COOP_SCHEMA} / Build {@link StoreFormat#COOP_MINIMUM_BUILD}, so no co-op record reaches
     * a store a Build 51 node could still open. The marker is re-read here (it is only ever raised); the Lua gate keeps
     * a node of an older build from writing once it is raised.
     */
    private void requireCoopStoreLevel(final ProfileIntent intent, final String what) throws IOException {
        boolean coop = false;
        for (final BlobRef ref : intent.afterImages()) {
            switch (ref.recordType()) {
                case ProfileRecords.TYPE_INVITE, ProfileRecords.TYPE_VOTE, ProfileRecords.TYPE_SALVAGE -> coop = true;
                case ProfileRecords.TYPE_PROFILE -> {
                    final String key = blobStageKey(ref);
                    final Object staged = this.client.callBounded(bound(ref.recordType()), "GET", key);
                    if (!(staged instanceof byte[] framed)) {
                        throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the staged after-image " + describe(ref)
                            + " is missing (stage it again); nothing was written");
                    }
                    if (ProfileRecords.needsCoopStore(ref.recordType(), decodeAfterImage(ref, framed, key))) coop = true;
                }
                default -> { }
            }
            if (coop) break;
        }
        if (!coop) return;
        final StoreFormat.Marker marker = storeFormat();
        if (!marker.covers(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD)) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, what + " installs a co-op record but the store marker "
                + meta() + " is schema " + marker.schema() + ", minimum build " + marker.minimumBuild() + "; the store's feature level must be"
                + " raised to schema " + StoreFormat.COOP_SCHEMA + " and Build " + StoreFormat.COOP_MINIMUM_BUILD + " (requireStoreFeature)"
                + " before the first co-op record. Nothing was written");
        }
    }

    @Override
    public CommitResult commitIntent(final UUID operationId, final CoopWriteGuard guard) throws IOException {
        return finish(operationId, guard, false);
    }

    @Override
    public CommitResult resolveIntent(final UUID operationId, final CoopWriteGuard guard) throws IOException {
        return finish(operationId, guard, true);
    }

    /**
     * COMMIT, or the roll-forward of recovery. Reads the intent and the staged after-images, builds the
     * install and release images, and runs one script that re-validates and installs everything.
     */
    private CommitResult finish(final UUID op, final CoopWriteGuard guard, final boolean resolve) throws IOException {
        final String what = (resolve ? "resolving intent " : "committing intent ") + op;
        requireOperation(guard, op, what);
        requireFormat2Read(resolve ? "resolveIntent" : "commitIntent");
        ensureWritable();
        final Optional<ProfileIntent> stored = intent(op);
        if (stored.isEmpty()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": no intent with that operation id is stored");
        }
        final ProfileIntent intent = stored.get();
        if (!intent.sameRequest(guard.operation().requestDigest())) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the stored intent has another request digest");
        }
        final UUID resource = storedResource(op, guard, intent);
        if (intent.phase() == ProfileIntent.Phase.ABORTED && !resolve) {
            throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, what + ": operation " + op + " was aborted; it cannot"
                + " commit and nothing was written");
        }
        if (intent.phase().terminal()) {
            return CommitResult.replayed(op, receipt(resource, op).orElse(null), what + ": already " + intent.phase());
        }
        if (resolve && intent.phase() != ProfileIntent.Phase.COMMITTED) {
            return CommitResult.unknown(op, what + ": the intent is " + intent.phase() + " and still pending; the engine decides"
                + " whether to commit or abort it");
        }
        final boolean forward = intent.phase() == ProfileIntent.Phase.COMMITTED;
        final Map<UUID, Byte> hints = new HashMap<>();
        for (final BlobRef ref : intent.afterImages()) hints.put(ref.id(), ref.recordType());
        final ProfileReceipt receipt = new ProfileReceipt(op, intent.requestDigest(), intent.kind(), ProfileReceipt.Outcome.COMMITTED,
            new byte[0], System.currentTimeMillis());
        final Call call = call(resource, receipt);
        call.arg(forward ? "1" : "0");
        call.check(Expected.record(op, intent.revision()), intentKey(op));
        final Map<UUID, Expected> afterByRecord = new LinkedHashMap<>();
        for (final Expected after : intent.expectedAfter()) {
            if (!forward) {
                final Expected named = guard.expected(after.domain(), after.resource(), after.member(), after.epoch());
                if (named.revision() != after.revision()) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the guard expects " + named.describe()
                        + " at " + named.revision() + " but the intent recorded " + after.revision());
                }
            }
            call.check(after, recordKey(after, op, hints, what));
            afterByRecord.put(recordIdentity(after), after);
        }
        final ProfileIntent finalized = forward ? intent.at(ProfileIntent.Phase.FINALIZED, "committed")
            : intent.at(ProfileIntent.Phase.COMMITTED, null).at(ProfileIntent.Phase.FINALIZED, "committed");
        call.key(intentKey(op));
        call.key(blobsKey(op));
        call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, finalized.revision(), ProfileRecords.intentPayload2(finalized), intentKey(op)));
        call.arg(Long.toString(finalized.revision()));
        // The deadline the script compares with Redis TIME before any write (an invite accept carries the invite's expiry).
        call.arg(Long.toString(intent.deadlineMillis()));
        // After-images: read the staged payloads (bounded), decode for the predicate fields, install.
        final List<BlobRef> images = intent.afterImages();
        final List<Object> payloads = readBlobs(op, images.size());
        final Set<UUID> installed = new java.util.HashSet<>();
        final List<Object[]> additions = new ArrayList<>();
        final List<Object[]> removals = new ArrayList<>();
        call.arg(Integer.toString(images.size()));
        final List<String[]> installFields = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            final BlobRef ref = images.get(i);
            requireInstallable(ref, what);
            if (!(payloads.get(i) instanceof byte[] frame)) throw new IOException(what + ": after-image " + i + " is missing from " + blobsKey(op));
            final Expected after = afterByRecord.get(recordIdentity(ref));
            if (after == null) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": after-image " + describe(ref)
                    + " has no entry in the intent's version vector");
            }
            final long expectedRevision = after.revision() == CoopWriteGuard.ABSENT ? 0L : after.revision() + 1L;
            if (ref.revision() != expectedRevision) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": after-image " + describe(ref)
                    + " is at revision " + ref.revision() + ", the vector requires " + expectedRevision);
            }
            // The staged frame is unframed and checked against its reference (format, type, revision, digest).
            final Object decoded = decodeAfterImage(ref, frame, blobsKey(op) + " " + i);
            installed.add(recordIdentity(ref));
            final String[] fields = {"", "", "", ""};
            String names = meta();
            switch (decoded) {
                case PlayerAccount account -> fields[0] = account.activeProfile() == null ? "" : account.activeProfile().toString();
                case AuroraProfile profile -> {
                    // Identity, one-way kind and P-for-membership checks against the stored record (the script's M
                    // check makes the read exact: a record that moves in between fails the vector, never these checks).
                    if (!forward && after.revision() != CoopWriteGuard.ABSENT) {
                        FileProfileRepository.checkProfileImage(profile(profile.id()).orElse(null), profile, intent);
                    }
                    names = namesKey(profile.accountId());
                    fields[0] = CuteNames.key(profile.name());
                    fields[1] = profile.accountId().toString();
                    fields[2] = profile.status() == ProfileStatus.DELETED ? "1" : "0";
                    fields[3] = profile.id().toString();
                }
                case MemberStateHead head -> fields[0] = head.snapshotId() == null ? "" : head.snapshotId().toString();
                case ProfileInvite invite -> {
                    final boolean open = invite.status() == ProfileInvite.Status.OPEN;
                    final String member = indexMember(invite.createdAtMillis(), invite.id());
                    (open ? additions : removals).add(new Object[] {indexKey(ProfileIndexPage.Kind.INVITES_BY_PROFILE, invite.profileId(), null, null), member, MAX_INDEXED_RECORDS});
                    (open ? additions : removals).add(new Object[] {indexKey(ProfileIndexPage.Kind.INVITES_BY_INVITEE, invite.invitee(), null, null), member, MAX_INDEXED_RECORDS});
                }
                case KickVote vote -> {
                    final boolean open = vote.status().occupiesSlot();   // OPEN or PASSED: the same slot the FILE indexes keep
                    final String member = indexMember(vote.createdAtMillis(), vote.id());
                    (open ? additions : removals).add(new Object[] {indexKey(ProfileIndexPage.Kind.VOTES_BY_PROFILE, vote.profileId(), null, null), member, MAX_OPEN_VOTES});
                    (open ? additions : removals).add(new Object[] {indexKey(ProfileIndexPage.Kind.VOTES_BY_TARGET, vote.target(), null, null), member, MAX_INDEXED_RECORDS});
                }
                case ProfileSalvage salvage -> {
                    // Same index semantics as FILE: outstanding (not DELIVERED) records by profile, AVAILABLE ones separately.
                    final String member = indexMember(salvage.createdAtMillis(), salvage.id());
                    (salvage.outstanding() ? additions : removals).add(new Object[] {
                        indexKey(ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, salvage.profileId(), null, null), member, MAX_INDEXED_RECORDS});
                    (salvage.state() == ProfileSalvage.State.AVAILABLE ? additions : removals).add(new Object[] {
                        indexKey(ProfileIndexPage.Kind.SALVAGE_AVAILABLE_BY_PROFILE, salvage.profileId(), null, null), member, MAX_INDEXED_RECORDS});
                }
                default -> { }
            }
            call.key(recordKey(after, op, hints, what));
            call.key(names);
            installFields.add(new String[] {Integer.toString(i), Long.toString(ref.revision()), Integer.toString(ref.recordType()),
                fields[0], fields[1], fields[2], fields[3]});
        }
        for (final String[] fields : installFields) {
            for (final String field : fields) call.arg(field);
        }
        // Releases: reserved records the operation does not rewrite lose their reservation (one increment).
        final List<Expected> releases = new ArrayList<>();
        for (final Expected after : intent.expectedAfter()) {
            if (installed.contains(recordIdentity(after))) continue;
            if (after.revision() == CoopWriteGuard.ABSENT) continue;
            releases.add(after);
        }
        appendReleases(call, releases, op, hints, what);
        // Compared records (same revision before and after, not installed) must not carry another operation's reservation.
        final Map<Expected, String> comparedKeys = new LinkedHashMap<>();
        final Set<Expected> comparedSet = new java.util.HashSet<>();
        for (final Expected after : intent.expectedAfter()) {
            if (installed.contains(recordIdentity(after)) || after.revision() == CoopWriteGuard.ABSENT) continue;
            if (after.domain() == Domain.RECORD && after.resource().equals(op)) continue;
            for (final Expected before : intent.expectedBefore()) {
                if (before.sameRecord(after) && compareOnly(before, after)) {
                    comparedKeys.put(after, recordKey(after, op, hints, what));
                    comparedSet.add(after);
                }
            }
        }
        appendCompared(call, comparedKeys, comparedSet);
        appendPins(call, intent);
        // Intent indexes to leave, record indexes to join.
        final Map<String, Integer> intentIndexes = intentIndexes(intent);
        final String intentMember = indexMember(intent.createdAtMillis(), op);
        call.arg(Integer.toString(intentIndexes.size() + removals.size()));
        for (final String key : intentIndexes.keySet()) call.key(key);
        for (final Object[] removal : removals) call.key((String) removal[0]);
        for (int i = 0; i < intentIndexes.size(); i++) call.arg(intentMember);
        for (final Object[] removal : removals) call.arg((String) removal[1]);
        call.arg(Integer.toString(additions.size()));
        for (final Object[] addition : additions) call.key((String) addition[0]);
        for (final Object[] addition : additions) {
            call.arg((String) addition[1]);
            call.arg(Integer.toString((Integer) addition[2]));
        }
        final List<String> installKeys = new ArrayList<>(images.size());
        for (final BlobRef ref : images) installKeys.add(recordKey(afterByRecord.get(recordIdentity(ref)), op, hints, what));
        final CommitResult result = run(what, RedisProfileScripts.COMMIT_INTENT, guard, call, resource);
        if (result.status() == CommitResult.Status.COMMITTED) verifyInstalled(what, images, installKeys);
        return result;
    }

    /**
     * Reads every installed after-image back once (bounded) and checks that the record hash holds a readable
     * format-2 frame at the reference's revision and digest. The commit is atomic and already applied, so a
     * mismatch is logged as an error for the operator rather than reported as a refusal; a failed read is a
     * warning.
     */
    private void verifyInstalled(final String what, final List<BlobRef> images, final List<String> keys) {
        for (int i = 0; i < images.size(); i++) {
            final BlobRef ref = images.get(i);
            final String key = keys.get(i);
            try {
                final ProfileRecords.Frame frame = record(key, ref.recordType());
                if (frame == null) {
                    SourbyLogger.error("AWF MultiProfile: " + what + " installed " + describe(ref) + " but " + key + " holds no record");
                } else if (frame.format() != ProfileRecords.FORMAT_2 || frame.revision() != ref.revision()
                    || !MessageDigest.isEqual(ProfileRecords.sha256(frame.payload()), ref.sha256())) {
                    SourbyLogger.error("AWF MultiProfile: " + what + " installed " + describe(ref) + " but " + key + " reads back as format "
                        + frame.format() + " revision " + frame.revision() + " with another payload; the store needs an operator");
                }
            } catch (final IOException | RuntimeException unreadable) {
                SourbyLogger.warn("AWF MultiProfile: " + what + " installed " + describe(ref) + " but " + key + " could not be read back ("
                    + unreadable.getMessage() + ")");
            }
        }
    }

    @Override
    public CommitResult abortIntent(final UUID op, final CoopWriteGuard guard) throws IOException {
        final String what = "aborting intent " + op;
        requireOperation(guard, op, what);
        requireFormat2Read("abortIntent");
        ensureWritable();
        final Optional<ProfileIntent> stored = intent(op);
        if (stored.isEmpty()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": no intent with that operation id is stored");
        }
        final ProfileIntent intent = stored.get();
        if (!intent.sameRequest(guard.operation().requestDigest())) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the stored intent has another request digest");
        }
        final UUID resource = storedResource(op, guard, intent);
        if (intent.phase() == ProfileIntent.Phase.ABORTED) {
            return CommitResult.replayed(op, receipt(resource, op).orElse(null), what + ": already aborted");
        }
        if (intent.committed()) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the intent is " + intent.phase()
                + " and rolls forward; it cannot be aborted");
        }
        final Map<UUID, Byte> hints = new HashMap<>();
        for (final BlobRef ref : intent.afterImages()) hints.put(ref.id(), ref.recordType());
        final ProfileReceipt receipt = new ProfileReceipt(op, intent.requestDigest(), intent.kind(), ProfileReceipt.Outcome.ABORTED,
            new byte[0], System.currentTimeMillis());
        final Call call = call(resource, receipt);
        call.check(Expected.record(op, intent.revision()), intentKey(op));
        // Like the FILE abort: the vector compares the intent's own revision and the records this operation reserved
        // (they sit at their post-reservation revision under its reservation, which nothing else may move); a record
        // the intent only compared may have moved since the prepare, and an abort must still be able to clear the
        // operation's reservations, or the account stays locked behind a PREPARED intent for good.
        final List<Expected> releases = new ArrayList<>();
        for (final Expected after : intent.expectedAfter()) {
            if (after.domain() == Domain.RECORD && after.resource().equals(op)) continue;
            final Expected named = guard.expected(after.domain(), after.resource(), after.member(), after.epoch());
            if (named.revision() != after.revision()) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the guard expects " + named.describe()
                    + " at " + named.revision() + " but the intent recorded " + after.revision());
            }
            // The pre-reservation entry comes from the intent itself (the abort guard carries post-reservation
            // revisions, so matchingBefore, which compares the guard with the pre-reservation vector, does not apply).
            Expected before = null;
            for (final Expected candidate : intent.expectedBefore()) {
                if (candidate.sameRecord(after)) before = candidate;
            }
            if (before == null) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": " + after.describe()
                    + " is in the post-reservation vector but not in the pre-reservation one");
            }
            if (after.revision() == CoopWriteGuard.ABSENT || compareOnly(before, after)) {
                // Not this abort's to check: run() -> checkVector would otherwise add it back from the guard and
                // compare a compare-only record at its prepare-time revision (STALE once it moved) or probe an absent
                // record with no type (INVALID_REQUEST). FILE's abort compares only the intent and its reservations.
                call.exempt(after);
                continue;
            }
            call.check(after, recordKey(after, op, hints, what));
            releases.add(after);
        }
        final ProfileIntent aborted = intent.at(ProfileIntent.Phase.ABORTED, "aborted");
        call.key(intentKey(op));
        call.key(blobsKey(op));
        call.arg(ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, aborted.revision(), ProfileRecords.intentPayload2(aborted), intentKey(op)));
        call.arg(Long.toString(aborted.revision()));
        appendReleases(call, releases, op, hints, what);
        appendPins(call, intent);
        final Map<String, Integer> indexes = intentIndexes(intent);
        final String member = indexMember(intent.createdAtMillis(), op);
        call.arg(Integer.toString(indexes.size()));
        for (final String key : indexes.keySet()) call.key(key);
        for (int i = 0; i < indexes.size(); i++) call.arg(member);
        return run(what, RedisProfileScripts.ABORT_INTENT, guard, call, resource);
    }

    @Override
    public Optional<ProfileIntent> intent(final UUID operationId) throws IOException {
        requireFormat2Read("intent");
        final String key = intentKey(operationId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_INTENT);
        return frame == null ? Optional.empty() : Optional.of(ProfileRecords.intent2(frame, operationId, key));
    }

    @Override
    public Optional<ProfileReceipt> receipt(final UUID resource, final UUID operationId) throws IOException {
        requireFormat2Read("receipt");
        // Retained kinds in the resource's hash, permanent kinds (Build 54) in its permanent hash: a lookup knows no kind.
        for (final boolean permanent : new boolean[] {false, true}) {
            final String key = receiptsKey(resource, permanent);
            final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_PAGE), "HGET", key, operationId.toString());
            if (stored instanceof byte[] bytes) return Optional.ofNullable(decodeReceipt(bytes, resource, operationId, key + " " + operationId));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ indexes

    @Override
    public ProfileIndexPage index(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch,
                                  final ProfileIndexPage.Cursor cursor) throws IOException {
        requireFormat2Read("index");
        if (kind.streamScoped() != (member != null && epoch != null) || (member == null) != (epoch == null)) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, kind + " listing: member and epoch are required for"
                + " stream-scoped kinds and must be null otherwise");
        }
        final long generation = indexGeneration(kind, resource, member, epoch);
        if (cursor != null && (cursor.kind() != kind || !cursor.resource().equals(resource) || cursor.generation() != generation)) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "the listing cursor belongs to another index or"
                + " generation; restart the listing");
        }
        final List<ProfileIndexPage.Entry> entries = new ArrayList<>(ProfileIndexPage.LISTING_PAGE);
        switch (kind) {
            case PINS_BY_STREAM -> {
                final List<ProfileIndexPage.Entry> all = new ArrayList<>();
                for (final Map.Entry<UUID, Long> pin : readPins(resource, member, epoch).entrySet()) {
                    all.add(new ProfileIndexPage.Entry(0L, pin.getKey(), pin.getValue()));
                }
                all.sort(null);
                for (final ProfileIndexPage.Entry entry : all) {
                    if (cursor != null && entry.id().compareTo(cursor.lastId()) <= 0) continue;
                    entries.add(entry);
                    if (entries.size() >= ProfileIndexPage.LISTING_PAGE) break;
                }
            }
            case SNAPSHOTS_BY_STREAM -> {
                // Ordered by R (the head revision each snapshot was published at), reported in the time field.
                final String min = cursor == null ? "-inf" : "(" + cursor.lastCreatedAtMillis();
                final List<?> flat = list(this.client.call("ZRANGEBYSCORE", memberSnapIndexKey(resource, member, epoch), min, "+inf",
                    "WITHSCORES", "LIMIT", "0", Integer.toString(ProfileIndexPage.LISTING_PAGE)));
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    final UUID id = uuid(RedisClient.text(flat.get(i)), memberSnapIndexKey(resource, member, epoch));
                    final long revision;
                    try {
                        revision = (long) Double.parseDouble(RedisClient.text(flat.get(i + 1)));
                    } catch (final NumberFormatException malformed) {
                        throw new IOException("snapshot index " + memberSnapIndexKey(resource, member, epoch) + " has a malformed score");
                    }
                    entries.add(new ProfileIndexPage.Entry(revision, id, 0L));
                }
            }
            default -> {
                final String key = indexKey(kind, resource, null, null);
                final String min = cursor == null ? "-" : "(" + indexMember(cursor.lastCreatedAtMillis(), cursor.lastId());
                final List<?> members = list(this.client.call("ZRANGEBYLEX", key, min, "+", "LIMIT", "0",
                    Integer.toString(ProfileIndexPage.LISTING_PAGE)));
                for (final Object text : members) entries.add(parseIndexMember(RedisClient.text(text), key));
            }
        }
        try {
            return new ProfileIndexPage(kind, resource, member, epoch, generation, 0, 0L, entries);
        } catch (final IllegalArgumentException corrupt) {
            throw new IOException("index " + kind + " of " + resource + " is corrupt: " + corrupt.getMessage() + "; the listing is locked"
                + " until the index is rebuilt offline");
        }
    }

    private long indexGeneration(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch) throws IOException {
        final String stored = RedisClient.text(this.client.call("HGET", indexGenerationKey(), indexScope(kind, resource, member, epoch)));
        if (stored == null) return 0L;
        try {
            return CoopWriteGuard.counter(Long.parseLong(stored), "index generation");
        } catch (final IllegalArgumentException malformed) {
            throw new IOException("index generation of " + kind + " " + resource + " is malformed");
        }
    }

    // ------------------------------------------------------------------ membership records (reads)

    @Override
    public Optional<ProfileInvite> invite(final UUID inviteId) throws IOException {
        requireFormat2Read("invite");
        final String key = inviteKey(inviteId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_INVITE);
        return frame == null ? Optional.empty() : Optional.of(ProfileRecords.invite2(frame, inviteId, key));
    }

    @Override
    public Optional<KickVote> vote(final UUID voteId) throws IOException {
        requireFormat2Read("vote");
        final String key = voteKey(voteId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_VOTE);
        return frame == null ? Optional.empty() : Optional.of(ProfileRecords.vote2(frame, voteId, key));
    }

    @Override
    public Optional<ProfileSalvage> salvage(final UUID salvageId) throws IOException {
        requireFormat2Read("salvage");
        final String key = salvageKey(salvageId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_SALVAGE);
        return frame == null ? Optional.empty() : Optional.of(ProfileRecords.salvage2(frame, salvageId, key));
    }

    // ================================================================== format-2 plumbing

    /** One script call being assembled: the fixed header, the vector, then the method's own keys and arguments. */
    private static final class Call {
        final List<Object> keys = new ArrayList<>();
        final List<Object> args = new ArrayList<>();
        final List<Expected> vector = new ArrayList<>();
        final List<Object> vectorKeys = new ArrayList<>();
        final List<Object> vectorArgs = new ArrayList<>();
        /** Guard entries deliberately not compared by this call (an abort's compare-only and created records). */
        final List<Expected> exempt = new ArrayList<>();
        /** The receipt record the script stores on success, or {@code null} for a prepare. */
        final byte[] receipt;

        Call(final byte[] receipt) {
            this.receipt = receipt;
        }

        void check(final Expected expected, final String key) {
            if (has(expected)) return;
            this.vector.add(expected);
            this.vectorKeys.add(key);
            this.vectorArgs.add(Long.toString(expected.revision()));
            this.vectorArgs.add(expected.domain() == Domain.ACCOUNT ? "0" : "-1");
        }

        void exempt(final Expected expected) {
            this.exempt.add(expected);
        }

        boolean has(final Expected expected) {
            for (final Expected entry : this.vector) {
                if (entry.sameRecord(expected)) return true;
            }
            for (final Expected entry : this.exempt) {
                if (entry.sameRecord(expected)) return true;
            }
            return false;
        }

        void key(final String key) {
            this.keys.add(key);
        }

        void arg(final Object arg) {
            this.args.add(arg);
        }

        Object[] assemble(final List<Object> fixedKeys, final List<Object> fixedArgs) {
            final List<Object> call = new ArrayList<>();
            call.add(fixedKeys.size() + this.vectorKeys.size() + this.keys.size());
            call.addAll(fixedKeys);
            call.addAll(this.vectorKeys);
            call.addAll(this.keys);
            call.addAll(fixedArgs);
            call.add(Integer.toString(this.vector.size()));
            call.addAll(this.vectorArgs);
            call.addAll(this.args);
            return call.toArray();
        }
    }

    /** A new call whose receipt (when not {@code null}) is stored under {@code resource} on success. */
    private Call call(final UUID resource, final ProfileReceipt receipt) throws IOException {
        return new Call(receipt == null ? null : receiptFrame(resource, receipt));
    }

    /** A COMMITTED receipt of the guard's operation (single-step mutations). */
    private static ProfileReceipt committedReceipt(final CoopWriteGuard guard) {
        return new ProfileReceipt(guard.operation().operationId(), guard.operation().requestDigest(), guard.operation().kind(),
            ProfileReceipt.Outcome.COMMITTED, new byte[0], System.currentTimeMillis());
    }

    /**
     * The receipt a direct commit stores, or {@code null} when none is kept: the offline migration (one
     * migration operation converts several records of one resource, and a stored receipt would answer the
     * second commit as a replay) and the high-frequency {@link CoopOperation.Kind#SAVE_MEMBER} and
     * {@link CoopOperation.Kind#LOGIN} commits (COOP-DESIGN §9 caps permanent receipts for financial and
     * delivery operations only; a save or login is idempotent through the head revision and the session
     * generation, and a million of them would exhaust the per-resource receipt cap and refuse further saves).
     */
    private static ProfileReceipt receiptFor(final CoopWriteGuard guard) {
        return guard.operation().kind().permanentReceipt() ? committedReceipt(guard) : null;
    }

    /** The guard's reservations as the comma-separated list a direct script compares a stored reservation with. */
    private static String reservationsArg(final CoopWriteGuard guard) {
        final StringBuilder out = new StringBuilder();
        for (final UUID reservation : guard.reservations()) {
            if (!out.isEmpty()) out.append(',');
            out.append(reservation);
        }
        return out.toString();
    }

    private byte[] receiptFrame(final UUID resource, final ProfileReceipt receipt) throws IOException {
        final ProfileReceipt.Page page = new ProfileReceipt.Page(resource, 0, 0L, List.of(receipt));
        return ProfileRecords.frame2(ProfileRecords.TYPE_PAGE, 0L, ProfileRecords.receiptPagePayload2(page), receiptsKey(resource) + " " + receipt.operationId());
    }

    private List<Object> fixedKeys(final CoopWriteGuard guard, final UUID resource) {
        final List<Object> keys = new ArrayList<>(6);
        keys.add(meta());
        keys.add(guard.profile() == null ? meta() : this.coop.key(CoopClaimFabric.Kind.PROFILE, guard.profile().profileId()));
        keys.add(guard.actor() == null ? meta() : this.coop.key(CoopClaimFabric.Kind.PLAYER, guard.actor().playerId()));
        keys.add(guard.affected() == null ? meta() : this.coop.key(CoopClaimFabric.Kind.PLAYER, guard.affected().playerId()));
        final boolean permanent = guard.operation().kind().permanent();
        keys.add(receiptsKey(resource, permanent));
        keys.add(receiptDigestKey(resource, permanent));
        return keys;
    }

    private List<Object> fixedArgs(final CoopWriteGuard guard, final byte[] receipt) {
        final List<Object> args = new ArrayList<>(12);
        args.add(guard.operation().kind() == CoopOperation.Kind.MIGRATION ? "1" : "0");
        args.add(Integer.toString(StoreFormat.MAX_SCHEMA));
        args.add(guard.operation().operationId().toString());
        args.add(HEX.formatHex(guard.operation().requestDigest()));
        args.add(guard.profile() == null ? "" : guard.profile().session());
        args.add(guard.profile() == null ? "0" : Long.toString(guard.profile().fence()));
        args.add(guard.actor() == null ? "" : guard.actor().session());
        args.add(guard.actor() == null ? "0" : Long.toString(guard.actor().fence()));
        args.add(guard.affected() == null ? "" : guard.affected().session());
        args.add(guard.affected() == null ? "0" : Long.toString(guard.affected().fence()));
        args.add(receipt == null ? "" : receipt);
        args.add(Long.toString(ProfileReceipt.Page.MAX_RECEIPTS_PER_RESOURCE));
        return args;
    }

    /**
     * Every entry of the guard's vector the method did not name itself joins the script's comparison, so a
     * guard that carries more than the mutation's own records (the switch commit names both profiles'
     * {@code M} and the player's {@code R} on each) is compared completely, as the FILE repository does.
     * The operation's own intent record is left to the intent methods.
     */
    private void checkVector(final Call call, final CoopWriteGuard guard, final String what) throws IOException {
        final UUID op = guard.operation().operationId();
        for (final Expected entry : guard.vector()) {
            if (call.has(entry)) continue;
            if (entry.domain() == Domain.RECORD && entry.resource().equals(op)) continue;
            call.check(entry, recordKey(entry, op, Map.of(), what));
        }
    }

    /**
     * Runs one format-2 script with {@code WAITAOF} on the same connection when configured. A refusal
     * throws the typed exception and wrote nothing. A lost reply is reconciled against the stored receipt
     * or intent; an applied write whose AOF confirmation did not arrive is UNKNOWN. Every entry of the
     * guard's vector is compared ({@link #checkVector}).
     */
    private CommitResult run(final String what, final String script, final CoopWriteGuard guard, final Call call, final UUID resource)
        throws IOException {
        ensureWritable();
        checkVector(call, guard, what);
        final UUID op = guard.operation().operationId();
        final Object[] keysAndArgs = call.assemble(fixedKeys(guard, resource), fixedArgs(guard, call.receipt));
        final int bound = bound(ProfileRecords.TYPE_INTENT);
        final Object reply;
        Object aof = null;
        int waitMillis = 0;
        try {
            if (this.waitForAof) {
                waitMillis = Math.max(1, this.client.remainingTimeoutMillis() / 2);
                final List<Object> replies = this.client.evalThenBounded(bound, script, keysAndArgs,
                    new Object[] {"WAITAOF", "1", "0", Integer.toString(waitMillis)});
                reply = replies.get(0);
                aof = replies.get(1);
            } else {
                reply = this.client.evalBounded(bound, script, keysAndArgs);
            }
        } catch (final RedisClient.RedisError error) {
            throw new IOException(what + ": the write script failed: " + error.getMessage(), error);
        } catch (final IOException lost) {
            return reconcile(what, guard, resource, lost, proofOf(script));
        }
        final CommitResult result = interpret(what, guard, call, op, resource, reply, proofOf(script));
        if (result.status() == CommitResult.Status.COMMITTED && this.waitForAof && !confirmed(aof, waitMillis)) {
            return CommitResult.unknown(op, what + " was applied by Redis but not confirmed in its append-only file within "
                + waitMillis + " ms; hold the gate and re-read the receipt or intent before any effect follows");
        }
        if (result.status() == CommitResult.Status.COMMITTED && call.receipt != null && !guard.operation().kind().permanent()) pruneReceipts(resource);
        return result;
    }

    /**
     * Keeps at most {@link ProfileReceipt#RETAINED_PER_RESOURCE} receipts of the retained kinds per resource (COOP-DESIGN §9
     * bounds): when the hash grows past it, the oldest by decision time are dropped from both receipt hashes. Permanent
     * kinds (Build 54) use separate hashes that are never pruned; the write script refuses a new one past
     * {@link ProfileReceipt.Page#MAX_RECEIPTS_PER_RESOURCE} ({@code LIMIT}, before any write). A dropped receipt
     * can no longer replay or refuse its operation id; the retention is sized so that any reconcile of a lost
     * reply (which happens right after the write) still finds its receipt. Best effort: a failure is logged.
     */
    private void pruneReceipts(final UUID resource) {
        try {
            final long count = number(this.client.call("HLEN", receiptsKey(resource)));
            if (count <= ProfileReceipt.RETAINED_PER_RESOURCE) return;
            final List<?> flat = list(this.client.callBounded(bound(ProfileRecords.TYPE_PAGE), "HGETALL", receiptsKey(resource)));
            final List<ProfileReceipt> receipts = new ArrayList<>();
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                final String field = RedisClient.text(flat.get(i));
                if (field == null || !(flat.get(i + 1) instanceof byte[] bytes)) continue;
                final ProfileReceipt receipt = decodeReceipt(bytes, resource, uuid(field, receiptsKey(resource)), receiptsKey(resource) + " " + field);
                if (receipt != null) receipts.add(receipt);
            }
            receipts.sort(Comparator.comparingLong(ProfileReceipt::decidedAtMillis).thenComparing(ProfileReceipt::operationId));
            final int excess = receipts.size() - ProfileReceipt.RETAINED_PER_RESOURCE;
            if (excess <= 0) return;
            final List<Object[]> commands = new ArrayList<>(2 * excess);
            for (int i = 0; i < excess; i++) {
                final String id = receipts.get(i).operationId().toString();
                commands.add(new Object[] {"HDEL", receiptsKey(resource), id});
                commands.add(new Object[] {"HDEL", receiptDigestKey(resource, false), id});
            }
            this.client.pipeline(commands);
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("AWF MultiProfile: pruning the receipts of " + resource + " failed (" + failed.getMessage()
                + "); they are pruned after the next commit");
        }
    }

    private CommitResult interpret(final String what, final CoopWriteGuard guard, final Call call, final UUID op, final UUID resource,
                                   final Object reply, final Proof proof) throws IOException {
        final List<?> fields = list(reply);
        if (fields.isEmpty()) throw new IOException(what + ": the write script returned a malformed reply");
        final String status = RedisClient.text(fields.get(0));
        final String detail = fields.size() > 1 ? RedisClient.text(fields.get(1)) : "";
        switch (status == null ? "" : status) {
            case "OK" -> {
                final ProfileReceipt receipt = receiptOf(guard, resource);
                return CommitResult.committed(op, receipt, what + " committed");
            }
            case "REPLAYED" -> {
                final ProfileReceipt receipt = fields.size() > 1 && fields.get(1) instanceof byte[] bytes && bytes.length > 0
                    ? decodeReceipt(bytes, resource, op, receiptsKey(resource, guard.operation().kind().permanent()) + " " + op) : null;
                if (receipt != null) requireOutcome(what, op, receipt.outcome(), proof);
                return CommitResult.replayed(op, receipt, what + ": operation " + op + " was already decided; nothing changed");
            }
            case "INTENT" -> {
                final int tag = Integer.parseInt(detail);
                final ProfileIntent.Phase phase = ProfileIntent.Phase.fromTag(tag, intentKey(op));
                if (phase.terminal()) return CommitResult.replayed(op, receipt(resource, op).orElse(null), what + ": already " + phase);
                if (phase == ProfileIntent.Phase.COMMITTED) {
                    return CommitResult.unknown(op, what + ": the intent is COMMITTED and not yet finalized; resolve it");
                }
                return CommitResult.replayed(op, null, what + ": the intent is already " + phase + "; nothing changed");
            }
            case "LEGACY" -> throw new ProfileOperationException(ProfileErrorCode.MIGRATION_REQUIRED, what + ": the Redis store is"
                + " format 1; co-op operations need the offline format-2 migration. Nothing was written");
            case "FORMAT" -> throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, what + " was refused: " + detail
                + ". Nothing was written");
            case "DIGEST" -> throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": operation " + op
                + " was already used with another request; nothing was written");
            case "FENCED" -> {
                final String which = fields.size() > 3 ? RedisClient.text(fields.get(3)) : "claim";
                final String claim = switch (which) {
                    case "P" -> "the profile claim P on " + (guard.profile() == null ? "?" : guard.profile().profileId());
                    case "Q" -> "the player claim Q of " + (guard.actor() == null ? "?" : guard.actor().playerId());
                    case "Q2" -> "the player claim Q of " + (guard.affected() == null ? "?" : guard.affected().playerId());
                    default -> "the claim";
                };
                throw guard.fenced(claim, "expired".equals(detail) ? "the lease has expired"
                    : "another session holds it; Redis has fence " + (fields.size() > 2 ? RedisClient.text(fields.get(2)) : "?"));
            }
            case "STALE" -> {
                final long stored;
                final int index;
                try {
                    stored = Long.parseLong(detail);
                    index = Integer.parseInt(RedisClient.text(fields.get(2))) - 1;
                } catch (final RuntimeException malformed) {
                    throw new IOException(what + ": the write script reported a malformed stale revision");
                }
                if (index < 0 || index >= call.vector.size()) throw new IOException(what + ": the write script reported an unknown vector entry");
                final Expected entry = call.vector.get(index);
                guard.check(entry.domain(), entry.resource(), entry.member(), entry.epoch(), stored);
                throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, entry.describe() + " is at revision " + stored
                    + " but operation " + op + " expected " + entry.revision() + "; nothing was written");
            }
            case "ACTIVE" -> {
                final UUID active = "-".equals(detail) ? null : uuid(detail, "active profile");
                guard.checkActive(active);
                throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, what + ": the account's active profile is "
                    + (active == null ? "none" : active) + "; nothing was written");
            }
            case "RESERVED" -> throw new ProfileOperationException(ProfileErrorCode.RECOVERY_REQUIRED, what + ": a record carries the"
                + " reservation of pending operation " + detail + "; resolve that intent first. Nothing was written");
            case "PENDING" -> throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": a direct commit cannot"
                + " install or clear a reservation (stored " + detail + ", written " + (fields.size() > 2 ? RedisClient.text(fields.get(2)) : "?")
                + "); intents do that. Nothing was written");
            case "NAME" -> throw new ProfileOperationException(ProfileErrorCode.NAME_TAKEN, what + ": the name is already used by profile "
                + detail + " of the same account; nothing was written");
            case "LIMIT" -> throw new ProfileOperationException(ProfileErrorCode.CAPACITY_EXCEEDED, what + " was refused: the bound on "
                + detail + " is reached; retry later or resolve pending operations. Nothing was written");
            case "EXPIRED" -> throw new ProfileOperationException(ProfileErrorCode.TIMEOUT, what + ": the intent passed its deadline "
                + detail + " by the Redis clock (" + (fields.size() > 2 ? RedisClient.text(fields.get(2)) : "?") + "); it can only be"
                + " aborted. Nothing was written");
            case "DECIDED" -> throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the intent is already"
                + " committed and rolls forward; nothing was written");
            case "NOINTENT" -> throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": no intent is stored for"
                + " operation " + op);
            case "MISSING" -> throw new IOException(what + ": " + detail + " is missing (staged data expired before its commit); nothing was written");
            case "REFUSED" -> throw new IOException(what + " was refused: " + detail);
            default -> throw new IOException(what + ": unexpected reply " + status);
        }
    }

    /**
     * A stored receipt replays a call only when it records the decision that call makes: a commit (direct or of an
     * intent) finding an ABORTED receipt is stale (FILE throws the same), an abort finding a COMMITTED one is invalid.
     */
    private static void requireOutcome(final String what, final UUID op, final ProfileReceipt.Outcome outcome, final Proof proof) {
        if (proof == Proof.ABORTED) {
            if (outcome != ProfileReceipt.Outcome.ABORTED) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": operation " + op + " was already "
                    + outcome + "; it cannot be aborted and nothing was written");
            }
        } else if (outcome != ProfileReceipt.Outcome.COMMITTED) {
            throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, what + ": operation " + op + " was already " + outcome
                + "; it cannot commit and nothing was written");
        }
    }

    /** The receipt this call wrote, re-read (bounded) so the result carries the stored record. */
    private ProfileReceipt receiptOf(final CoopWriteGuard guard, final UUID resource) throws IOException {
        try {
            return receipt(resource, guard.operation().operationId()).orElse(null);
        } catch (final IOException | ProfileOperationException failed) {
            return null;
        }
    }

    /** What a script leaves behind when it applies, so a lost reply can be reconciled against exactly that. */
    private enum Proof {
        /** A direct commit: its receipt (none for the kinds without a permanent receipt). */
        RECEIPT,
        /** PREPARE: the intent hash exists (PREPARED, or decided later by another step). */
        PREPARED,
        /** COMMIT or roll-forward: the intent is COMMITTED or FINALIZED. */
        COMMITTED,
        /** ABORT: the intent is ABORTED. */
        ABORTED
    }

    private static Proof proofOf(final String script) {
        if (script == RedisProfileScripts.PREPARE_INTENT) return Proof.PREPARED;
        if (script == RedisProfileScripts.COMMIT_INTENT) return Proof.COMMITTED;
        if (script == RedisProfileScripts.ABORT_INTENT) return Proof.ABORTED;
        return Proof.RECEIPT;
    }

    /**
     * A reply was lost. The request may have been applied, may still be in flight (a re-read on another
     * pooled connection can run before a request that is still in the network), or may never arrive, so the
     * outcome is established only by the evidence this very call writes: the receipt, or the intent phase the
     * script sets. Any other state is UNKNOWN, never "not applied" and never "applied": the caller holds the
     * gate and re-reads; a retry with the same operation id is safe because every script replays first. With
     * {@code wait-for-aof} the AOF is probed before an applied write counts as durable.
     */
    private CommitResult reconcile(final String what, final CoopWriteGuard guard, final UUID resource, final IOException lost,
                                   final Proof proof) throws IOException {
        final UUID op = guard.operation().operationId();
        final Optional<ProfileReceipt> receipt;
        final Optional<ProfileIntent> intent;
        try {
            receipt = receipt(resource, op);
            intent = receipt.isPresent() || proof == Proof.RECEIPT ? Optional.empty() : intent(op);
        } catch (final IOException | ProfileOperationException unreadable) {
            return CommitResult.unknown(op, what + ": the reply was lost (" + lost.getMessage() + ") and the receipt could not be"
                + " re-read (" + unreadable.getMessage() + "); hold the gate and re-read before any effect follows");
        }
        String evidence = null;
        if (receipt.isPresent()) {
            if (!receipt.get().sameRequest(guard.operation().requestDigest())) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": operation " + op
                    + " was already used with another request");
            }
            // A receipt of the opposite decision is not evidence this call applied: it is the refusal the script gives.
            requireOutcome(what, op, receipt.get().outcome(), proof);
            evidence = "its receipt";
        } else if (intent.isPresent()) {
            final ProfileIntent stored = intent.get();
            if (!stored.sameRequest(guard.operation().requestDigest())) {
                throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": operation " + op
                    + " was already used with another request");
            }
            final boolean applied = switch (proof) {
                case PREPARED -> true;
                case COMMITTED -> stored.committed();
                case ABORTED -> stored.phase() == ProfileIntent.Phase.ABORTED;
                case RECEIPT -> false;
            };
            if (applied) evidence = "the intent being " + stored.phase();
        }
        if (evidence == null) {
            return CommitResult.unknown(op, what + ": the reply was lost (" + lost.getMessage() + ") and re-reading found "
                + (intent.isPresent() ? "the intent " + intent.get().phase() : "no receipt or intent") + " for operation " + op
                + "; the request may still be applied, so nothing is assumed. Hold the gate and re-read before any effect follows."
                + (guard.operation().kind().permanentReceipt() || proof != Proof.RECEIPT
                    ? " A retry with the same operation id is replayed if it was applied"
                    : " This kind leaves no receipt: a retry is refused as stale if it was applied; the record decides"));
        }
        if (this.waitForAof) {
            final int waitMillis = Math.max(1, this.client.remainingTimeoutMillis() / 2);
            if (!aofConfirmed(waitMillis)) {
                return CommitResult.unknown(op, what + ": applied (the reply was lost) but not confirmed in the append-only file within "
                    + waitMillis + " ms; hold the gate and re-read before any effect follows");
            }
        }
        return CommitResult.committed(op, receipt.orElse(null), what + " was applied (" + evidence + "); its reply was lost and re-read");
    }

    /**
     * Whether everything written through this connection so far is in Redis's append-only file: with
     * {@code wait-for-aof} a fresh marker write is confirmed with {@code WAITAOF}; without it (or when Redis
     * cannot confirm) the writes count as durable as Redis itself is. Used before an outcome that was read
     * back from memory (not acknowledged by its own write) is accepted as applied.
     */
    @Override
    public boolean durable() throws IOException {
        ensureOpen();
        if (!this.waitForAof) return true;
        return aofConfirmed(Math.max(1, this.client.remainingTimeoutMillis() / 2));
    }

    /** The AOF has fsynced everything before a fresh marker write on this connection: {@code WAITAOF} after an {@code HINCRBY}. */
    private boolean aofConfirmed(final int waitMillis) throws IOException {
        final List<Object> replies = this.client.pipeline(List.of(new Object[] {"HINCRBY", meta(), "aof-probe", "1"},
            new Object[] {"WAITAOF", "1", "0", Integer.toString(waitMillis)}));
        return confirmed(replies.get(1), waitMillis);
    }

    /**
     * The format-1 path's {@code WAITAOF} handling, unchanged from Build 50: a Redis that refuses the command turns
     * the confirmation off for this process with one WARN (format-1 writes are then acknowledged from memory); an
     * unconfirmed reply is logged and counted. Format-2 writes use {@link #confirmed} and never turn it off.
     */
    private void legacyConfirmed(final Object reply, final int waitMillis) {
        if (reply instanceof RedisClient.RedisError unsupported) {
            this.waitForAof = false;
            SourbyLogger.warn("AWF MultiProfile: Redis cannot confirm writes to its append-only file ("
                + unsupported.getMessage() + "); format-1 profile writes are acknowledged from Redis's memory from now on."
                + " Enable appendonly on Redis 7.2+, or set aurora.awf.multiprofile.redis.wait-for-aof: false");
            return;
        }
        confirmed(reply, waitMillis);
    }

    /**
     * Whether a {@code WAITAOF} reply confirms the local AOF. A Redis that cannot answer it (appendonly off, or
     * older than 7.2) confirms nothing: the write is applied but UNKNOWN to its caller, exactly as a timed-out
     * confirmation is, until the operator enables appendonly or sets {@code wait-for-aof: false} (then no
     * confirmation is requested at all). The option is never switched off by itself.
     */
    private boolean confirmed(final Object reply, final int waitMillis) {
        if (reply instanceof RedisClient.RedisError unsupported) {
            final long now = System.nanoTime();
            final long last = this.lastUnconfirmedWarn.get();
            this.unconfirmed.incrementAndGet();
            if (now - last >= UNCONFIRMED_WARN_NANOS && this.lastUnconfirmedWarn.compareAndSet(last, now)) {
                SourbyLogger.warn("AWF MultiProfile: Redis cannot confirm profile writes in its append-only file ("
                    + unsupported.getMessage() + "); every such write is applied but reported UNKNOWN to its caller."
                    + " Enable appendonly on Redis 7.2+, or set aurora.awf.multiprofile.redis.wait-for-aof: false to"
                    + " acknowledge writes from Redis's memory");
            }
            return false;
        }
        if (reply instanceof List<?> counts && !counts.isEmpty() && counts.getFirst() instanceof Long local && local >= 1L) {
            return true;
        }
        final long total = this.unconfirmed.incrementAndGet();
        final long now = System.nanoTime();
        final long last = this.lastUnconfirmedWarn.get();
        if (now - last >= UNCONFIRMED_WARN_NANOS && this.lastUnconfirmedWarn.compareAndSet(last, now)) {
            SourbyLogger.warn("AWF MultiProfile: Redis applied a profile write but did not confirm it in its append-only"
                + " file within " + waitMillis + " ms (" + total + " such write(s) so far); it is lost if Redis crashes"
                + " before its next fsync");
        }
        return false;
    }

    // ------------------------------------------------------------------ format-2 helpers

    private void requireActor(final CoopWriteGuard guard, final UUID expectedPlayer, final String what) {
        final CoopOperation.Kind kind = guard.operation().kind();
        if (guard.actor() == null) {
            if (kind == CoopOperation.Kind.MIGRATION || kind == CoopOperation.Kind.CLEANUP) return;
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": this mutation requires the player claim Q;"
                + " nothing was written");
        }
        if (expectedPlayer != null && !guard.actor().playerId().equals(expectedPlayer)) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the player claim Q is on account "
                + guard.actor().playerId() + ", not on " + expectedPlayer + "; nothing was written");
        }
    }

    private void requireProfileClaim(final CoopWriteGuard guard, final UUID profileId, final String what) {
        if (guard.profile() == null) {
            if (guard.operation().kind() == CoopOperation.Kind.MIGRATION) return;
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": this mutation requires the profile claim P;"
                + " nothing was written");
        }
        if (!guard.profile().profileId().equals(profileId)) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the profile claim P is on profile "
                + guard.profile().profileId() + ", not on " + profileId + "; nothing was written");
        }
    }

    private void requireOperation(final CoopWriteGuard guard, final UUID op, final String what) {
        if (!guard.operation().operationId().equals(op)) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the guard belongs to operation "
                + guard.operation().operationId());
        }
    }

    /** {@code next} must be the expected revision plus one (0 after ABSENT); the offline migration sets its own starting revisions. */
    private static void requireNext(final long expected, final long next, final CoopWriteGuard guard, final String what) {
        CoopWriteGuard.counter(next, what + " revision");
        if (guard.operation().kind() == CoopOperation.Kind.MIGRATION) return;
        final long required = expected == CoopWriteGuard.ABSENT ? 0L : CoopWriteGuard.next(expected, what);
        if (next != required) {
            throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the after-image is at revision " + next
                + " but the expected revision " + (expected == CoopWriteGuard.ABSENT ? "none" : expected) + " requires " + required
                + "; nothing was written");
        }
    }

    private static String activeArg(final UUID expectedActive) {
        if (expectedActive == null) return "";
        return CoopWriteGuard.NO_ACTIVE_PROFILE.equals(expectedActive) ? "-" : expectedActive.toString();
    }

    /** Format-2 reads and writes need a format-2 store; a format-1 store needs the migration, an unknown one is refused. */
    private void requireFormat2Read(final String method) throws IOException {
        ensureOpen();
        final StoreFormat.Decision decision = StoreFormat.gate(this.marker);
        switch (decision.gate()) {
            case FORMAT_2 -> { }
            case LEGACY -> {
                if (this.marker.migration() == StoreFormat.MigrationState.IN_PROGRESS) return;
                throw new ProfileOperationException(ProfileErrorCode.MIGRATION_REQUIRED, method + "(): " + decision.reason());
            }
            case REFUSED -> {
                // A migration in progress may read and (under a MIGRATION guard) write; the script gate decides writes.
                if (this.marker.format() == StoreFormat.CURRENT && this.marker.migration() == StoreFormat.MigrationState.IN_PROGRESS) return;
                throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, method + "(): " + decision.reason());
            }
        }
    }

    /** Format-1 record writers refuse once the store left format 1 (the format-2 methods are the write path then). */
    private void requireLegacyWrite(final String method) {
        if (this.marker.legacyFormat()) return;
        throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, method + "() writes a format-1 record, but the Redis"
            + " store is format " + this.marker.format() + " (" + this.marker.migration() + "); nothing was written");
    }

    /** Format-1 snapshot reads are meaningful in a format-1 store, or while its migration reads them. */
    private void requireLegacySnapshots(final String method) {
        if (this.marker.legacyFormat() || this.marker.migration() == StoreFormat.MigrationState.IN_PROGRESS) return;
        throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, method + "() reads format-1 profile snapshots, but"
            + " the Redis store is format " + this.marker.format() + "; member streams hold the state (memberSnapshot)");
    }

    private void requireInstallable(final BlobRef ref, final String what) {
        switch (ref.recordType()) {
            case ProfileRecords.TYPE_ACCOUNT, ProfileRecords.TYPE_PROFILE, ProfileRecords.TYPE_BINDING, ProfileRecords.TYPE_SHARED,
                 ProfileRecords.TYPE_INVITE, ProfileRecords.TYPE_VOTE, ProfileRecords.TYPE_SALVAGE -> {
                if (ref.member() != null) throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": after-image "
                    + describe(ref) + " names a member but is not a stream record");
            }
            case ProfileRecords.TYPE_MEMBER_HEAD -> {
                if (ref.member() == null) throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": after-image "
                    + describe(ref) + " is a member head without member and epoch");
            }
            default -> throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": after-image " + describe(ref)
                + " is a " + ProfileRecords.typeName(ref.recordType()) + ", which an intent cannot install in this build (member snapshots"
                + " are published through publishMemberSnapshot and pinned; journals, archives, intents and pages are not after-images)");
        }
    }

    /**
     * Unframes a staged after-image (a format-2 frame) and checks it against its reference (type, revision,
     * payload length and digest), then decodes it by type, checking id and stream; the decoded record.
     */
    private Object decodeAfterImage(final BlobRef ref, final byte[] framed, final String where) throws IOException {
        final ProfileRecords.Frame frame = ProfileRecords.unframe(framed, ProfileRecords.FORMAT_2, ref.recordType(), where);
        if (frame.revision() != ref.revision() || frame.payload().length != ref.length()
            || !MessageDigest.isEqual(ProfileRecords.sha256(frame.payload()), ref.sha256())) {
            throw new IOException("the after-image " + where + " of " + describe(ref) + " does not match its reference (revision, length"
                + " or digest)");
        }
        final Object decoded = switch (ref.recordType()) {
            case ProfileRecords.TYPE_ACCOUNT -> ProfileRecords.account2(frame, ref.id(), where);
            case ProfileRecords.TYPE_PROFILE -> ProfileRecords.profile2(frame, ref.id(), where);
            case ProfileRecords.TYPE_BINDING -> ProfileRecords.binding2(frame, ref.id(), where);
            case ProfileRecords.TYPE_SHARED -> ProfileRecords.shared2(frame, ref.id(), where);
            case ProfileRecords.TYPE_INVITE -> ProfileRecords.invite2(frame, ref.id(), where);
            case ProfileRecords.TYPE_VOTE -> ProfileRecords.vote2(frame, ref.id(), where);
            case ProfileRecords.TYPE_SALVAGE -> ProfileRecords.salvage2(frame, ref.id(), where);
            case ProfileRecords.TYPE_MEMBER_HEAD -> ProfileRecords.memberHead2(frame, ref.id(), ref.member(), ref.epoch(), where);
            default -> throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, "after-image " + describe(ref)
                + " has a record type an intent cannot install");
        };
        return decoded;
    }

    /** The receipt resource of an intent: the P profile, else the actor's account, else the first vector resource. */
    private static UUID receiptResource(final CoopWriteGuard guard, final ProfileIntent intent) {
        if (guard.profile() != null) return guard.profile().profileId();
        if (guard.actor() != null) return guard.actor().playerId();
        return intent.expectedAfter().getFirst().resource();
    }

    /** The receipt resource the prepare recorded in the intent hash; falls back to the guard's choice. */
    private UUID storedResource(final UUID op, final CoopWriteGuard guard, final ProfileIntent intent) throws IOException {
        final String stored = RedisClient.text(this.client.call("HGET", intentKey(op), "resource"));
        return stored == null ? receiptResource(guard, intent) : uuid(stored, intentKey(op) + " resource");
    }

    /**
     * The pre-reservation entry of {@code after}: the guard must name it, and the intent's before vector must agree.
     * A record the intent reserves moves by exactly one; a record it only compares (same revision before and after:
     * an invite, vote or salvage record, which carries no reservation field) moves by none. Every membership plan
     * reserves the profile's metadata record. Same rule as the FILE repository's step plan.
     */
    private static Expected matchingBefore(final ProfileIntent intent, final Expected after, final CoopWriteGuard guard, final String what) {
        final Expected named = guard.expected(after.domain(), after.resource(), after.member(), after.epoch());
        for (final Expected before : intent.expectedBefore()) {
            if (before.sameRecord(after)) {
                if (before.revision() != named.revision()) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": the guard expects " + named.describe()
                        + " at " + named.revision() + " but the intent recorded " + before.revision());
                }
                if (before.domain() == Domain.ACCOUNT && before.revision() == CoopWriteGuard.ABSENT) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": accounts use revision 0, never ABSENT");
                }
                if (compareOnly(before, after)) return before;
                final long required = before.revision() == CoopWriteGuard.ABSENT ? CoopWriteGuard.ABSENT : before.revision() + 1L;
                if (after.revision() != required) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": " + after.describe() + " is expected at "
                        + after.revision() + " after reservation, but the reservation of a record at " + before.revision() + " gives " + required);
                }
                return before;
            }
        }
        throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": " + after.describe()
            + " is in the post-reservation vector but not in the pre-reservation one");
    }

    /** Whether an intent compares a record without reserving it (same expected revision before and after). */
    private static boolean compareOnly(final Expected before, final Expected after) {
        return before.revision() == after.revision();
    }

    /** A reserved after-image: the stored record re-encoded at revision + 1 carrying the operation's reservation. */
    private record Reserved(long revision, byte[] frame) {}

    private Reserved reserve(final Expected before, final String key, final UUID op, final String what) throws IOException {
        final byte type = switch (before.domain()) {
            case ACCOUNT -> ProfileRecords.TYPE_ACCOUNT;
            case METADATA -> ProfileRecords.TYPE_PROFILE;
            case SHARED -> ProfileRecords.TYPE_SHARED;
            case HEAD -> ProfileRecords.TYPE_MEMBER_HEAD;
            case RECORD -> {
                if (!key.startsWith(this.namespace + "binding:")) {
                    throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": " + before.describe()
                        + " cannot carry a reservation in this build (only accounts, profiles, shared state, heads and bindings can)");
                }
                yield ProfileRecords.TYPE_BINDING;
            }
        };
        final ProfileRecords.Frame frame = record(key, type);
        final long storedRevision = frame == null ? (before.domain() == Domain.ACCOUNT ? 0L : CoopWriteGuard.ABSENT) : frame.revision();
        if (storedRevision != before.revision()) {
            throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, before.describe() + " is at revision " + storedRevision
                + " but operation " + op + " expected " + before.revision() + "; another operation changed it and nothing was written");
        }
        final byte[] encoded;
        final long next;
        switch (before.domain()) {
            case ACCOUNT -> {
                final PlayerAccount account = frame == null ? PlayerAccount.empty(before.resource(), this.defaultSlots)
                    : ProfileRecords.account2(frame, before.resource(), key);
                final PlayerAccount reserved = account.withPendingIntent(op);
                next = reserved.revision();
                encoded = ProfileRecords.frame2(type, next, ProfileRecords.accountPayload2(reserved), key);
            }
            case METADATA -> {
                final AuroraProfile reserved = ProfileRecords.profile2(frame, before.resource(), key).withPendingIntent(op);
                next = reserved.revision();
                encoded = ProfileRecords.frame2(type, next, ProfileRecords.profilePayload2(reserved), key);
            }
            case SHARED -> {
                final ProfileSharedState reserved = ProfileRecords.shared2(frame, before.resource(), key).withPendingIntent(op);
                next = reserved.revision();
                encoded = ProfileRecords.frame2(type, next, ProfileRecords.sharedPayload2(reserved), key);
            }
            case HEAD -> {
                final MemberStateHead reserved = ProfileRecords.memberHead2(frame, before.resource(), before.member(), before.epoch(), key)
                    .withPendingIntent(op);
                next = reserved.revision();
                encoded = ProfileRecords.frame2(type, next, ProfileRecords.memberHeadPayload2(reserved), key);
            }
            default -> {
                final ProfileRecords.Binding2 binding = ProfileRecords.binding2(frame, before.resource(), key);
                next = CoopWriteGuard.next(binding.revision(), "binding of " + before.resource());
                encoded = ProfileRecords.frame2(type, next, ProfileRecords.bindingPayload2(binding.binding(), next, op), key);
            }
        }
        return new Reserved(next, encoded);
    }

    /** Release images: the reserved records re-encoded at revision + 1 without the reservation. */
    private void appendReleases(final Call call, final List<Expected> releases, final UUID op, final Map<UUID, Byte> hints, final String what)
        throws IOException {
        final List<String> keys = new ArrayList<>();
        final List<Reserved> images = new ArrayList<>();
        for (final Expected after : releases) {
            final String key = recordKey(after, op, hints, what);
            final Reserved released = release(after, key, op, what);
            if (released == null) continue;
            keys.add(key);
            images.add(released);
        }
        call.arg(Integer.toString(keys.size()));
        for (final String key : keys) call.key(key);
        for (final Reserved image : images) {
            call.arg(Long.toString(image.revision()));
            call.arg(image.frame());
        }
    }

    private Reserved release(final Expected after, final String key, final UUID op, final String what) throws IOException {
        final byte type = switch (after.domain()) {
            case ACCOUNT -> ProfileRecords.TYPE_ACCOUNT;
            case METADATA -> ProfileRecords.TYPE_PROFILE;
            case SHARED -> ProfileRecords.TYPE_SHARED;
            case HEAD -> ProfileRecords.TYPE_MEMBER_HEAD;
            case RECORD -> {
                if (after.resource().equals(op)) yield (byte) -1;
                if (!key.startsWith(this.namespace + "binding:")) yield (byte) -1;
                yield ProfileRecords.TYPE_BINDING;
            }
        };
        if (type < 0) return null;
        final ProfileRecords.Frame frame = record(key, type);
        if (frame == null) return null;
        if (frame.revision() != after.revision()) {
            throw new ProfileOperationException(ProfileErrorCode.STALE_STATE, after.describe() + " is at revision " + frame.revision()
                + " but operation " + op + " expected " + after.revision() + "; nothing was written");
        }
        switch (after.domain()) {
            case ACCOUNT -> {
                final PlayerAccount account = ProfileRecords.account2(frame, after.resource(), key);
                if (!op.equals(account.pendingIntent())) return null;
                final PlayerAccount released = account.withPendingIntent(null);
                return new Reserved(released.revision(), ProfileRecords.frame2(type, released.revision(), ProfileRecords.accountPayload2(released), key));
            }
            case METADATA -> {
                final AuroraProfile profile = ProfileRecords.profile2(frame, after.resource(), key);
                if (!op.equals(profile.pendingIntent())) return null;
                final AuroraProfile released = profile.withPendingIntent(null);
                return new Reserved(released.revision(), ProfileRecords.frame2(type, released.revision(), ProfileRecords.profilePayload2(released), key));
            }
            case SHARED -> {
                final ProfileSharedState shared = ProfileRecords.shared2(frame, after.resource(), key);
                if (!op.equals(shared.pendingIntent())) return null;
                final ProfileSharedState released = shared.withPendingIntent(null);
                return new Reserved(released.revision(), ProfileRecords.frame2(type, released.revision(), ProfileRecords.sharedPayload2(released), key));
            }
            case HEAD -> {
                final MemberStateHead head = ProfileRecords.memberHead2(frame, after.resource(), after.member(), after.epoch(), key);
                if (!op.equals(head.pendingIntent())) return null;
                final MemberStateHead released = head.withPendingIntent(null);
                return new Reserved(released.revision(), ProfileRecords.frame2(type, released.revision(), ProfileRecords.memberHeadPayload2(released), key));
            }
            default -> {
                final ProfileRecords.Binding2 binding = ProfileRecords.binding2(frame, after.resource(), key);
                if (!op.equals(binding.pendingIntent())) return null;
                final long next = CoopWriteGuard.next(binding.revision(), "binding of " + after.resource());
                return new Reserved(next, ProfileRecords.frame2(type, next, ProfileRecords.bindingPayload2(binding.binding(), next, null), key));
            }
        }
    }

    /** The keys of compare-only vector entries, for the script's reservation check (count, then keys). */
    private static void appendCompared(final Call call, final Map<Expected, String> keys, final Set<Expected> compared) {
        final List<String> selected = new ArrayList<>();
        for (final Map.Entry<Expected, String> entry : keys.entrySet()) {
            if (compared.contains(entry.getKey())) selected.add(entry.getValue());
        }
        call.arg(Integer.toString(selected.size()));
        for (final String key : selected) call.key(key);
    }

    private void appendPins(final Call call, final ProfileIntent intent) {
        final List<SnapshotRef> pins = new ArrayList<>();
        for (final SnapshotRef pin : intent.pins()) {
            if (pin.member() != null) pins.add(pin);
        }
        call.arg(Integer.toString(pins.size()));
        for (final SnapshotRef pin : pins) call.key(pinsKey(pin.profileId(), pin.member(), pin.epoch()));
        for (final SnapshotRef pin : pins) call.arg(pin.snapshotId().toString());
    }

    /** The pending-intent indexes an intent belongs to, with their caps: global, every account touched, every profile touched. */
    private Map<String, Integer> intentIndexes(final ProfileIntent intent) {
        final Map<String, Integer> indexes = new LinkedHashMap<>();
        indexes.put(indexKey(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null), MAX_PENDING_GLOBAL);
        indexes.put(indexKey(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, intent.actor(), null, null), MAX_PENDING_PER_SCOPE);
        for (final ProfileIntent.ClaimRef claim : intent.playerClaims()) {
            indexes.put(indexKey(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, claim.id(), null, null), MAX_PENDING_PER_SCOPE);
        }
        if (intent.profileClaim() != null) {
            indexes.put(indexKey(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, intent.profileClaim().id(), null, null), MAX_PENDING_PER_SCOPE);
        }
        for (final Expected entry : intent.expectedAfter()) {
            switch (entry.domain()) {
                case ACCOUNT -> indexes.put(indexKey(ProfileIndexPage.Kind.INTENTS_BY_PLAYER, entry.resource(), null, null), MAX_PENDING_PER_SCOPE);
                case METADATA, SHARED, HEAD -> indexes.put(indexKey(ProfileIndexPage.Kind.INTENTS_BY_PROFILE, entry.resource(), null, null), MAX_PENDING_PER_SCOPE);
                default -> { }
            }
        }
        return indexes;
    }

    /** The staged after-images of an operation, bounded by the shared-record limit each. */
    private List<Object> readBlobs(final UUID op, final int count) throws IOException {
        if (count == 0) return List.of();
        final Object[] command = new Object[2 + count];
        command[0] = "HMGET";
        command[1] = blobsKey(op);
        for (int i = 0; i < count; i++) command[2 + i] = Integer.toString(i);
        final List<?> replies = list(this.client.callBounded(ProfileRecords.MAX_SHARED_PAYLOAD + ProfileRecords.OVERHEAD, command));
        if (replies.size() != count) throw new IOException(blobsKey(op) + " returned " + replies.size() + " after-images, expected " + count);
        return new ArrayList<>(replies);
    }

    /** The key of the record an expected entry names; RECORD-domain ids are resolved by the after-image types, then by probing. */
    private String recordKey(final Expected entry, final UUID op, final Map<UUID, Byte> hints, final String what) throws IOException {
        switch (entry.domain()) {
            case ACCOUNT -> {
                return accountKey(entry.resource());
            }
            case METADATA -> {
                return profileKey(entry.resource());
            }
            case SHARED -> {
                return sharedKey(entry.resource());
            }
            case HEAD -> {
                return headKey(entry.resource(), entry.member(), entry.epoch());
            }
            default -> {
                final UUID id = entry.resource();
                if (id.equals(op)) return intentKey(id);
                final Byte hint = hints.get(id);
                if (hint != null) return keyOfType(hint, id, what);
                final List<Object[]> probes = List.of(new Object[] {"EXISTS", bindingKey(id)}, new Object[] {"EXISTS", inviteKey(id)},
                    new Object[] {"EXISTS", voteKey(id)}, new Object[] {"EXISTS", salvageKey(id)}, new Object[] {"EXISTS", intentKey(id)});
                final List<Object> replies = this.client.pipeline(probes);
                final String[] keys = {bindingKey(id), inviteKey(id), voteKey(id), salvageKey(id), intentKey(id)};
                String found = null;
                for (int i = 0; i < keys.length; i++) {
                    if (number(replies.get(i)) == 1L) {
                        if (found != null) throw new IOException(what + ": record id " + id + " exists as more than one record type");
                        found = keys[i];
                    }
                }
                if (found == null) {
                    if (entry.revision() == CoopWriteGuard.ABSENT) {
                        throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": record " + id
                            + " is expected absent and no after-image names its type; the record type cannot be resolved");
                    }
                    return bindingKey(id);     // absent everywhere: the stale check reports it as missing
                }
                return found;
            }
        }
    }

    private String keyOfType(final byte type, final UUID id, final String what) {
        return switch (type) {
            case ProfileRecords.TYPE_ACCOUNT -> accountKey(id);
            case ProfileRecords.TYPE_PROFILE -> profileKey(id);
            case ProfileRecords.TYPE_BINDING -> bindingKey(id);
            case ProfileRecords.TYPE_SHARED -> sharedKey(id);
            case ProfileRecords.TYPE_INVITE -> inviteKey(id);
            case ProfileRecords.TYPE_VOTE -> voteKey(id);
            case ProfileRecords.TYPE_SALVAGE -> salvageKey(id);
            case ProfileRecords.TYPE_INTENT -> intentKey(id);
            default -> throw new ProfileOperationException(ProfileErrorCode.INVALID_REQUEST, what + ": " + ProfileRecords.typeName(type)
                + " records are not addressed by a record id");
        };
    }

    /** One identity per record for matching after-images with vector entries: {@code (domain-or-type, id, member, epoch)} folded into a UUID. */
    private static UUID recordIdentity(final Expected entry) {
        return identity(entry.domain().tag(), entry.resource(), entry.member(), entry.epoch());
    }

    private static UUID recordIdentity(final BlobRef ref) {
        final int domain = switch (ref.recordType()) {
            case ProfileRecords.TYPE_ACCOUNT -> Domain.ACCOUNT.tag();
            case ProfileRecords.TYPE_PROFILE -> Domain.METADATA.tag();
            case ProfileRecords.TYPE_SHARED -> Domain.SHARED.tag();
            case ProfileRecords.TYPE_MEMBER_HEAD -> Domain.HEAD.tag();
            default -> Domain.RECORD.tag();
        };
        return identity(domain, ref.id(), ref.member(), ref.epoch());
    }

    private static UUID identity(final int domain, final UUID id, final UUID member, final UUID epoch) {
        final byte[] digest = CoopOperation.digest(domain, id, member, epoch);
        return UUID.nameUUIDFromBytes(digest);
    }

    private static String describe(final BlobRef ref) {
        return ProfileRecords.typeName(ref.recordType()) + " " + ref.id() + (ref.member() == null ? "" : " member " + ref.member() + "/" + ref.epoch())
            + " at revision " + ref.revision();
    }

    private ProfileReceipt decodeReceipt(final byte[] bytes, final UUID resource, final UUID op, final String where) throws IOException {
        final ProfileReceipt.Page page = ProfileRecords.receiptPage2(
            ProfileRecords.unframe(bytes, ProfileRecords.FORMAT_2, ProfileRecords.TYPE_PAGE, where), resource, where);
        final ProfileReceipt receipt = page.find(op);
        if (receipt == null) throw new IOException("receipt record " + where + " does not hold operation " + op);
        return receipt;
    }

    /** Lexicographic member of an index set: zero-padded hex of the time, then of the id with sign bits flipped (sorts as UUID.compareTo). */
    private static String indexMember(final long createdAtMillis, final UUID id) {
        return String.format("%016x%016x%016x", createdAtMillis, id.getMostSignificantBits() ^ Long.MIN_VALUE,
            id.getLeastSignificantBits() ^ Long.MIN_VALUE);
    }

    private static ProfileIndexPage.Entry parseIndexMember(final String member, final String where) throws IOException {
        if (member == null || member.length() != 48) throw new IOException("index " + where + " holds a malformed entry; the listing is locked until the index is rebuilt");
        try {
            final long createdAt = Long.parseUnsignedLong(member.substring(0, 16), 16);
            final long msb = Long.parseUnsignedLong(member.substring(16, 32), 16) ^ Long.MIN_VALUE;
            final long lsb = Long.parseUnsignedLong(member.substring(32, 48), 16) ^ Long.MIN_VALUE;
            return new ProfileIndexPage.Entry(createdAt, new UUID(msb, lsb), 0L);
        } catch (final IllegalArgumentException malformed) {
            throw new IOException("index " + where + " holds a malformed entry; the listing is locked until the index is rebuilt");
        }
    }

    // ================================================================== ProfileMigrationStore (offline format 1 -> 2)

    /**
     * Redis has no migration lock. This refuses what it can observe: a session, profile-transaction or
     * player-session claim hash in the fabric namespace whose lease has not expired by Redis time and whose
     * incarnation is not this process. Nodes that hold no claim right now are not seen; stopping every node
     * first is the operator's procedure.
     */
    @Override
    public void requireNoOtherWriter() throws IOException {
        ensureOpen();
        final long now = redisNowMillis();
        final String[] prefixes = {claimPrefix(this.sessions.key(ProfileIndexPage.GLOBAL)),
            claimPrefix(this.coop.key(CoopClaimFabric.Kind.PROFILE, ProfileIndexPage.GLOBAL)),
            claimPrefix(this.coop.key(CoopClaimFabric.Kind.PLAYER, ProfileIndexPage.GLOBAL))};
        for (final String prefix : prefixes) {
            for (final String key : scanKeys(prefix)) {
                final List<?> fields = list(this.client.call("HMGET", key, "node", "incarnation", "expiresAt"));
                if (fields.size() < 3) continue;
                final String node = RedisClient.text(fields.get(0));
                final String incarnation = RedisClient.text(fields.get(1));
                final long expiresAt = number(fields.get(2));
                if (node == null || expiresAt <= now) continue;
                if (this.sessions.incarnation().equals(incarnation) || this.coop.incarnation().equals(incarnation)) continue;
                throw new IOException("another process still holds a live claim on this store (" + key + ", node " + node
                    + ", expires in " + (expiresAt - now) + " ms); stop every node that uses the Redis profile store before the"
                    + " migration. Nothing was written");
            }
        }
    }

    @Override
    public List<UUID> migrationAccountIds() throws IOException {
        final List<UUID> ids = new ArrayList<>(accountIds());
        ids.sort(null);
        return List.copyOf(ids);
    }

    @Override
    public List<UUID> migrationProfileIds() throws IOException {
        final List<UUID> ids = new ArrayList<>(profileIds());
        ids.sort(null);
        return List.copyOf(ids);
    }

    @Override
    public List<UUID> legacyProfileSnapshotIds(final UUID profileId) throws IOException {
        ensureOpen();
        final List<?> members = list(this.client.call("ZRANGE", snapIndexKey(profileId), "0", "-1"));
        final List<UUID> ids = new ArrayList<>(members.size());
        for (final Object member : members) ids.add(uuid(RedisClient.text(member), snapIndexKey(profileId)));
        return List.copyOf(ids);
    }

    @Override
    public int legacyArchiveCount() throws IOException {
        int count = 0;
        for (final String key : scanKeys(this.namespace + "legacy:")) {
            for (final String field : archiveFields(key)) {
                if (formatOf(archiveBytes(key, field), key + " " + field) == ProfileRecords.FORMAT) count++;
            }
        }
        return count;
    }

    @Override
    public int convertLegacyArchives() throws IOException {
        requireMigrating("convertLegacyArchives");
        int converted = 0;
        for (final String key : scanKeys(this.namespace + "legacy:")) {
            final UUID player = uuid(key.substring((this.namespace + "legacy:").length()), key);
            for (final String field : archiveFields(key)) {
                final String where = key + " " + field;
                final byte[] bytes = archiveBytes(key, field);
                if (formatOf(bytes, where) != ProfileRecords.FORMAT) continue;
                final ProfileSnapshot archive = ProfileRecords.snapshot(
                    ProfileRecords.unframe(bytes, ProfileRecords.FORMAT, ProfileRecords.TYPE_SNAPSHOT, where), player, where);
                final byte[] frame = ProfileRecords.frame2(ProfileRecords.TYPE_SNAPSHOT, archive.revision(),
                    ProfileRecords.legacyArchivePayload2(archive), where);
                migrationScript("converting the archive " + where, RedisProfileScripts.MIGRATE_ARCHIVE, 2, meta(), key, field, frame);
                converted++;
            }
        }
        return converted;
    }

    @Override
    public void writeMigratedMemberSnapshot(final ProfileSnapshot snapshot) throws IOException {
        requireMigrating("writeMigratedMemberSnapshot");
        if (!snapshot.member()) throw new IllegalArgumentException("a migrated member snapshot names member and epoch");
        final String snaps = memberSnapsKey(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch());
        final byte[] frame = ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_SNAPSHOT, snapshot.revision(),
            ProfileRecords.memberSnapshotPayload2(snapshot), snaps + " " + snapshot.snapshotId());
        migrationScript("installing the migrated member snapshot " + snapshot.snapshotId(), RedisProfileScripts.MIGRATE_MEMBER_SNAPSHOT,
            3, meta(), snaps, memberSnapIndexKey(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch()),
            snapshot.snapshotId().toString(), frame, Long.toString(snapshot.revision()));
    }

    @Override
    public void writeMigratedHead(final MemberStateHead head) throws IOException {
        requireMigrating("writeMigratedHead");
        final String key = headKey(head.profileId(), head.memberId(), head.epoch());
        migrationScript("installing the migrated head of " + head.describe(), RedisProfileScripts.MIGRATE_HEAD, 2, meta(), key,
            Long.toString(head.revision()), ProfileRecords.frame2(ProfileRecords.TYPE_MEMBER_HEAD, head.revision(),
                ProfileRecords.memberHeadPayload2(head), key), head.snapshotId() == null ? "" : head.snapshotId().toString());
    }

    @Override
    public int dropLegacyProfileSnapshots(final UUID profileId) throws IOException {
        requireMigrating("dropLegacyProfileSnapshots");
        final List<?> reply = migrationScript("dropping the format-1 snapshots of " + profileId, RedisProfileScripts.MIGRATE_DROP_SNAPSHOTS,
            4, meta(), profileKey(profileId), snapsKey(profileId), snapIndexKey(profileId));
        return reply.size() > 1 ? (int) Math.min(Integer.MAX_VALUE, number(reply.get(1))) : 0;
    }

    @Override
    public int retireTerminalJournals() throws IOException {
        requireMigrating("retireTerminalJournals");
        int retired = 0;
        String cursor = "0";
        do {
            final List<?> page = list(this.client.call("HSCAN", journalPlayerKey(), cursor, "COUNT", "256"));
            if (page.size() < 2) throw new IOException("HSCAN of " + journalPlayerKey() + " returned a malformed reply");
            cursor = RedisClient.text(page.get(0));
            final List<?> flat = list(page.get(1));
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                final String transitionId = RedisClient.text(flat.get(i));
                final String player = RedisClient.text(flat.get(i + 1));
                final UUID playerId = uuid(player, journalPlayerKey() + " " + transitionId);
                final String key = journalsKey(playerId);
                final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_TRANSITION), "HGET", key, transitionId);
                if (!(stored instanceof byte[] bytes)) continue;
                if (formatOf(bytes, key + " " + transitionId) != ProfileRecords.FORMAT) continue;
                final ProfileTransition journal = journal(key, transitionId, bytes);
                if (!journal.terminal()) {
                    throw new IOException("journal " + transitionId + " of " + playerId + " is " + journal.phase()
                        + "; the migration does not retire an unresolved switch");
                }
                migrationScript("retiring journal " + transitionId, RedisProfileScripts.MIGRATE_RETIRE_JOURNAL, 6, meta(), key,
                    journalMetaKey(playerId), journalPlayerKey(), pendingKey(), failedKey(), transitionId, player);
                retired++;
            }
        } while (!"0".equals(cursor));
        return retired;
    }

    @Override
    public String backup(final String backupDir) throws IOException {
        ensureOpen();
        if (StoreFormat.gate(storeFormat()).gate() == StoreFormat.Gate.FORMAT_2) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, "the store is already format 2; there is no"
                + " format-1 store to back up");
        }
        final List<String> fences = new ArrayList<>(3);
        fences.add(this.sessions.epochPrefix());
        for (final CoopClaimFabric.Kind kind : CoopClaimFabric.Kind.values()) fences.add(this.coop.fencePrefix(kind));
        return RedisProfileBackup.create(this.client, this.namespace, fences, backupDir).toString();
    }

    @Override
    public Optional<String> verifiedBackup(final String backupDir) throws IOException {
        return RedisProfileBackup.find(this.namespace, backupDir).map(Path::toString);
    }

    /**
     * Records that are not format 2: account, profile and binding frames, pre-profile archives and journals
     * by their frame's format byte; a non-empty format-1 snapshot hash or index of a profile as a whole.
     */
    @Override
    public List<String> legacyRecordsRemaining(final int limit) throws IOException {
        ensureOpen();
        final List<String> found = new ArrayList<>();
        for (final String kind : List.of("account:", "profile:", "binding:")) {
            final byte type = switch (kind) {
                case "account:" -> ProfileRecords.TYPE_ACCOUNT;
                case "profile:" -> ProfileRecords.TYPE_PROFILE;
                default -> ProfileRecords.TYPE_BINDING;
            };
            for (final String key : scanKeys(this.namespace + kind)) {
                if (found.size() >= limit) return List.copyOf(found);
                final Object stored = this.client.callBounded(bound(type), "HGET", key, "rec");
                if (stored instanceof byte[] bytes && formatOf(bytes, key) != ProfileRecords.FORMAT_2) found.add(key);
            }
        }
        for (final String key : scanKeys(this.namespace + "snaps:")) {
            if (found.size() >= limit) return List.copyOf(found);
            if (number(this.client.call("HLEN", key)) > 0L) found.add(key);
        }
        for (final String key : scanKeys(this.namespace + "snapidx:")) {
            if (found.size() >= limit) return List.copyOf(found);
            if (number(this.client.call("ZCARD", key)) > 0L) found.add(key);
        }
        for (final String key : scanKeys(this.namespace + "legacy:")) {
            for (final String field : archiveFields(key)) {
                if (found.size() >= limit) return List.copyOf(found);
                if (formatOf(archiveBytes(key, field), key + " " + field) != ProfileRecords.FORMAT_2) found.add(key + " " + field);
            }
        }
        for (final String key : scanKeys(this.namespace + "journals:")) {
            for (final Object field : list(this.client.call("HKEYS", key))) {
                if (found.size() >= limit) return List.copyOf(found);
                final String transitionId = RedisClient.text(field);
                final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_TRANSITION), "HGET", key, transitionId);
                if (stored instanceof byte[] bytes && formatOf(bytes, key + " " + transitionId) != ProfileRecords.FORMAT_2) {
                    found.add(key + " " + transitionId);
                }
            }
        }
        return List.copyOf(found);
    }

    /** The migration-store writes need the marker to say IN_PROGRESS (re-read now; the scripts re-check it atomically). */
    private void requireMigrating(final String method) throws IOException {
        final StoreFormat.Marker marker = storeFormat();
        if (marker.format() != StoreFormat.CURRENT || marker.migration() != StoreFormat.MigrationState.IN_PROGRESS) {
            throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, method + "() runs only while the store marker says"
                + " a migration is in progress; the Redis store is format " + marker.format() + " (" + marker.migration() + ")");
        }
    }

    /** Runs one {@link RedisProfileScripts#MIGRATION_HEADER} script; FORMAT and REFUSED replies fail the migration step. */
    private List<?> migrationScript(final String what, final String script, final int keyCount, final Object... keysAndArgs)
        throws IOException {
        ensureOpen();
        final Object[] call = new Object[1 + keysAndArgs.length];
        call[0] = keyCount;
        System.arraycopy(keysAndArgs, 0, call, 1, keysAndArgs.length);
        final Object reply;
        try {
            reply = this.client.eval(script, call);
        } catch (final RedisClient.RedisError error) {
            throw new IOException(what + ": the migration script failed: " + error.getMessage(), error);
        }
        final List<?> fields = list(reply);
        final String status = fields.isEmpty() ? null : RedisClient.text(fields.get(0));
        if ("OK".equals(status)) return fields;
        final String detail = fields.size() > 1 ? RedisClient.text(fields.get(1)) : status;
        if ("FORMAT".equals(status)) throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, what + " was refused: " + detail);
        throw new IOException(what + " was refused: " + detail);
    }

    /** The archive fields of a {@code legacy:} hash (every field but {@code latest}), bounded. */
    private List<String> archiveFields(final String key) throws IOException {
        final List<?> fields = list(this.client.call("HKEYS", key));
        if (fields.size() > 1024) throw new IOException(key + " holds " + fields.size() + " archives; inspect it");
        final List<String> names = new ArrayList<>(fields.size());
        for (final Object field : fields) {
            final String name = RedisClient.text(field);
            if (name != null && !"latest".equals(name)) names.add(name);
        }
        return names;
    }

    private byte[] archiveBytes(final String key, final String field) throws IOException {
        final Object stored = this.client.callBounded(bound(ProfileRecords.TYPE_SNAPSHOT), "HGET", key, field);
        if (!(stored instanceof byte[] bytes)) throw new IOException("the archive " + field + " of " + key + " is missing");
        return bytes;
    }

    /** The format byte of a framed record (byte 5, after the magic); fails for bytes that are not a record. */
    private static int formatOf(final byte[] frame, final String where) throws IOException {
        if (frame.length < ProfileRecords.OVERHEAD || java.nio.ByteBuffer.wrap(frame, 0, 4).getInt() != ProfileRecords.MAGIC) {
            throw new IOException("record " + where + " is not a profile record");
        }
        return frame[4] & 0xFF;
    }

    /** The key prefix of a claim-hash key built for the all-zero id. */
    private static String claimPrefix(final String probeKey) {
        return probeKey.substring(0, probeKey.length() - ProfileIndexPage.GLOBAL.toString().length());
    }

    private long redisNowMillis() throws IOException {
        final List<?> time = list(this.client.call("TIME"));
        if (time.size() < 2) throw new IOException("Redis TIME returned a malformed reply");
        return number(time.get(0)) * 1000L + number(time.get(1)) / 1000L;
    }

    /** Redis {@code TIME}: the clock every node shares, for intent deadlines compared across nodes. */
    @Override
    public long storeClockMillis() throws IOException {
        ensureOpen();
        return redisNowMillis();
    }

    /** Every key with {@code prefix} (bounded {@code SCAN}). */
    private List<String> scanKeys(final String prefix) throws IOException {
        ensureOpen();
        final Set<String> keys = new LinkedHashSet<>();
        String cursor = "0";
        do {
            final List<?> page = list(this.client.call("SCAN", cursor, "MATCH", prefix + "*", "COUNT", "1000"));
            if (page.size() < 2) throw new IOException("SCAN of " + prefix + "* returned a malformed reply");
            cursor = RedisClient.text(page.get(0));
            for (final Object key : list(page.get(1))) {
                final String text = RedisClient.text(key);
                if (text == null || !text.startsWith(prefix)) continue;
                keys.add(text);
                if (keys.size() > MAX_ENUMERATED) throw new IOException("more than " + MAX_ENUMERATED + " keys under " + prefix + "; refusing to enumerate");
            }
        } while (!"0".equals(cursor));
        return List.copyOf(keys);
    }

    // ------------------------------------------------------------------ import, for ProfileRepositoryMigration (format 1)

    /** Starts the one-way import; refused once anything has committed here, an import finished, or the store left format 1. */
    void beginImport() throws IOException {
        ensureOpen();
        expectOk("the migration", this.client.eval(IMPORT_BEGIN, 1, meta(), Long.toString(System.currentTimeMillis())));
    }

    void importAccount(final PlayerAccount account) throws IOException {
        final String key = accountKey(account.playerId());
        importHash(key, "rev", Long.toString(account.revision()), "rec",
            ProfileRecords.frame(ProfileRecords.TYPE_ACCOUNT, account.revision(), ProfileRecords.accountPayload(account), key));
    }

    void importProfile(final AuroraProfile profile) throws IOException {
        importHash(profileKey(profile.id()), "rev", Long.toString(profile.revision()), "rec", profileRecord(profile));
    }

    void importBinding(final ProfileWorldBinding binding) throws IOException {
        final String key = bindingKey(binding.profileId());
        importHash(key, "rev", "1", "rec",
            ProfileRecords.frame(ProfileRecords.TYPE_BINDING, 1L, ProfileRecords.bindingPayload(binding), key));
    }

    void importSnapshot(final ProfileSnapshot snapshot, final boolean latest) throws IOException {
        final UUID profileId = snapshot.profileId();
        importBlob(snapshot, snapsKey(profileId), profileKey(profileId), snapIndexKey(profileId),
            Long.toString(snapshot.revision()), latest);
    }

    void importLegacy(final ProfileSnapshot snapshot, final boolean latest) throws IOException {
        final String key = legacyKey(snapshot.profileId());
        importBlob(snapshot, key, key, meta(), "", latest);
    }

    void importTransition(final ProfileTransition transition) throws IOException {
        ensureOpen();
        final UUID playerId = transition.playerId();
        final String id = transition.transitionId().toString();
        final String key = journalsKey(playerId);
        final String terminal = transition.terminal() ? "1" : "0";
        final String updated = Long.toString(transition.updatedAtMillis());
        expectOk("importing transition journal " + id, this.client.eval(IMPORT_JOURNAL, 6, meta(), key,
            journalMetaKey(playerId), journalPlayerKey(), pendingKey(), failedKey(), id, playerId.toString(),
            ProfileRecords.frame(ProfileRecords.TYPE_TRANSITION, 1L, ProfileRecords.transitionPayload(transition),
                key + " " + id),
            terminal + " " + updated + " " + idOrDash(transition.fromSnapshotId()) + " " + idOrDash(transition.toSnapshotId()),
            terminal, transition.phase() == TransitionPhase.FAILED ? "1" : "0", updated));
    }

    /** Marks the import finished and accepts writes again. */
    void finishImport(final String summary) throws IOException {
        ensureOpen();
        expectOk("finishing the migration", this.client.eval(IMPORT_FINISH, 1, meta(), summary,
            Long.toString(System.currentTimeMillis())));
        this.writesRefused = null;
    }

    private void importHash(final String key, final Object... fieldsAndValues) throws IOException {
        ensureOpen();
        final Object[] call = new Object[3 + fieldsAndValues.length];
        call[0] = 2;
        call[1] = meta();
        call[2] = key;
        System.arraycopy(fieldsAndValues, 0, call, 3, fieldsAndValues.length);
        expectOk("importing " + key, this.client.eval(IMPORT_HASH, call));
    }

    private void importBlob(final ProfileSnapshot snapshot, final String hash, final String latestHash, final String index,
                            final String revision, final boolean latest) throws IOException {
        ensureOpen();
        final String stage = stageKey(snapshot.snapshotId());
        stage(stage, ProfileRecords.frame(ProfileRecords.TYPE_SNAPSHOT, snapshot.revision(), ProfileRecords.snapshotPayload(snapshot), stage));
        try {
            expectOk("importing snapshot " + snapshot.snapshotId(), this.client.eval(IMPORT_BLOB, 5, meta(), stage, hash,
                latestHash, index, snapshot.snapshotId().toString(), revision, latest ? "1" : "0"));
        } catch (final IOException | RuntimeException failed) {
            discard(stage);
            throw failed;
        }
    }

    // ------------------------------------------------------------------ format-1 commit plumbing

    /**
     * Runs one write script: KEYS[1] meta, KEYS[2] the claim's hash (meta without a claim), then
     * {@code keys}; ARGV[1..3] the claim, then {@code args}. An OK reply returns; FENCED and STALE throw
     * the typed refusal; anything else throws {@link IOException}. A refusal changed nothing.
     */
    private void commit(final String what, final String script, final ProfileWriteGuard guard, final Ack ack,
                        final List<String> keys, final List<Object> args) throws IOException {
        ensureWritable();
        final OwnerClaim claim = guard.claim();
        final List<Object> call = new ArrayList<>(6 + keys.size() + args.size());
        call.add(2 + keys.size());
        call.add(meta());
        call.add(claim == null ? meta() : claimKey(claim));
        call.addAll(keys);
        if (claim == null) {
            call.add("");
            call.add("");
            call.add("0");
        } else {
            call.add(claimSession(claim));
            call.add(claim.owner());
            call.add(Long.toString(claim.fence()));
        }
        call.addAll(args);
        final Object[] keysAndArgs = call.toArray();
        if (ack == Ack.AOF && this.waitForAof) {
            final int waitMillis = Math.max(1, this.client.remainingTimeoutMillis() / 2);
            final List<Object> replies = evalThenWait(script, keysAndArgs, waitMillis);
            interpret(what, guard, replies.get(0));
            legacyConfirmed(replies.get(1), waitMillis);
        } else {
            interpret(what, guard, this.client.eval(script, keysAndArgs));
        }
    }

    /**
     * {@code EVALSHA} (or {@code EVAL} when Redis does not have the script) and {@code WAITAOF} in one
     * pipeline, so both run on one connection: {@code WAITAOF} waits for that connection's last write.
     */
    private List<Object> evalThenWait(final String script, final Object[] keysAndArgs, final int waitMillis)
        throws IOException {
        return this.client.evalThen(script, keysAndArgs, new Object[] {"WAITAOF", "1", "0", Integer.toString(waitMillis)});
    }

    private static void interpret(final String what, final ProfileWriteGuard guard, final Object reply) throws IOException {
        final List<?> fields = list(reply);
        if (fields.isEmpty()) throw new IOException(what + ": the write script returned a malformed reply");
        final String status = RedisClient.text(fields.get(0));
        final String detail = fields.size() > 1 ? RedisClient.text(fields.get(1)) : "";
        switch (status == null ? "" : status) {
            case "OK" -> {
                return;
            }
            case "FENCED" -> {
                final OwnerClaim claim = guard.claim();
                final String epoch = fields.size() > 2 ? RedisClient.text(fields.get(2)) : "?";
                throw new ProfileOperationException(ProfileErrorCode.OWNERSHIP_CONFLICT, what + " was refused: the claim on "
                    + (claim == null ? "the profile" : claim.kind() + " " + claim.id() + " (fence " + claim.fence() + ")")
                    + ("expired".equals(detail) ? " has expired" : " no longer holds it (Redis has fence " + epoch + ")")
                    + "; nothing was written");
            }
            case "STALE" -> {
                final long stored;
                try {
                    stored = Long.parseLong(detail);
                } catch (final NumberFormatException malformed) {
                    throw new IOException(what + ": the write script reported a stale revision '" + detail + "'");
                }
                guard.check(what, stored);
                throw new IOException(what + ": the write script reported revision " + stored + " as stale");
            }
            case "FORMAT" -> throw new ProfileOperationException(ProfileErrorCode.STORE_FORMAT_REFUSED, what + " was refused: " + detail);
            case "MISSING" -> throw new IOException(what + ": " + detail + " expired before its commit; nothing was written");
            case "REFUSED" -> throw new IOException(what + " was refused: " + detail);
            default -> throw new IOException(what + ": unexpected reply " + status);
        }
    }

    private static void expectOk(final String what, final Object reply) throws IOException {
        final List<?> fields = list(reply);
        final String status = fields.isEmpty() ? null : RedisClient.text(fields.get(0));
        if ("OK".equals(status)) return;
        throw new IOException(what + " was refused: " + (fields.size() > 1 ? RedisClient.text(fields.get(1)) : status));
    }

    /** Writes a framed record alone under its staging key; the commit moves it or it expires. */
    private void stage(final String key, final byte[] record) throws IOException {
        final Object reply = this.client.call("SET", key, record, "PX", Long.toString(STAGE_TTL_MILLIS), "NX");
        if (reply == null) throw new IOException("the staging key " + key + " already exists; nothing was written");
    }

    /** Best effort: a staged record the commit did not take expires on its own anyway. */
    private void discard(final String stage) {
        try {
            this.client.call("DEL", stage);
        } catch (final IOException failed) {
            // Its TTL removes it.
        }
    }

    /** The hash a format-1 guard's claim is compared against: the session fabric's, or a co-op claim's. */
    private String claimKey(final OwnerClaim claim) {
        if ("session".equals(claim.kind())) return this.sessions.key(UUID.fromString(claim.id()));
        try {
            return this.coop.key(CoopClaimFabric.Kind.of(claim.kind()), UUID.fromString(claim.id()));
        } catch (final IllegalArgumentException unknown) {
            throw new IllegalArgumentException("the Redis profile repository is fenced by session or co-op claims, not "
                + claim.kind() + " claims");
        }
    }

    private String claimSession(final OwnerClaim claim) {
        final UUID owner = UUID.fromString(claim.owner());
        return "session".equals(claim.kind()) ? this.sessions.session(claim.node(), owner) : this.coop.session(claim.node(), owner);
    }

    private void ensureOpen() throws IOException {
        if (this.closed) throw new IOException("the Redis profile repository is closed");
    }

    private void ensureWritable() throws IOException {
        ensureOpen();
        final String refused = this.writesRefused;
        if (refused == null) return;
        // Another server may have migrated its file repository, or written, since this one started:
        // the repository is in use then, and this directory's profiles are no longer the ones to copy.
        final List<?> meta = list(this.client.call("HMGET", meta(), "commits", "imported"));
        if ((!meta.isEmpty() && number(meta.get(0)) > 0L) || (meta.size() > 1 && meta.get(1) != null)) {
            this.writesRefused = null;
            SourbyLogger.warn("AWF MultiProfile: the Redis repository is in use by now (migrated or written by another"
                + " server); profile writes are accepted, and the profiles in this server's file repository are not read");
            return;
        }
        throw new ProfileOperationException(ProfileErrorCode.IO_FAILURE, "profile writes are refused: " + refused);
    }

    // ------------------------------------------------------------------ startup checks

    /** Reads the store marker; refuses a store of a newer format or schema, warns about a migration left in progress. */
    private void checkFormat() throws IOException {
        final StoreFormat.Marker marker = storeFormat();
        if (marker.legacyFormat()) {
            this.client.call("HSETNX", meta(), StoreFormat.REDIS_FORMAT_FIELD, FORMAT);
            return;
        }
        final StoreFormat.Decision decision = StoreFormat.gate(marker);
        if (decision.gate() == StoreFormat.Gate.REFUSED) {
            if (marker.format() == StoreFormat.CURRENT && marker.migration() == StoreFormat.MigrationState.IN_PROGRESS) {
                SourbyLogger.error("AWF MultiProfile: " + decision.reason() + " (Redis " + meta() + ")");
                return;
            }
            throw new IllegalStateException("the Redis profile repository " + meta() + " is refused: " + decision.reason());
        }
    }

    /**
     * Refuses writes while this repository is format 1, empty, and the file repository still holds
     * profiles: a profile created now would make the import impossible (it copies only into an empty
     * repository). When this repository is in use already, or format 2, profiles left in the file
     * repository are reported, never read.
     */
    private void checkUnmigrated(final Path directory) throws IOException {
        final List<?> meta = list(this.client.call("HMGET", meta(), "commits", "imported"));
        final long commits = meta.isEmpty() ? 0L : number(meta.get(0));
        final boolean imported = meta.size() > 1 && meta.get(1) != null;
        final Path accounts = directory.resolve("accounts");
        if (!Files.isDirectory(accounts)) return;
        final boolean fileData;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(accounts, "*.awfp")) {
            fileData = files.iterator().hasNext();
        }
        if (!fileData) return;
        if (commits == 0L && !imported && this.marker.legacyFormat()) {
            this.writesRefused = "the file repository " + directory + " holds profiles that are not in Redis yet; run"
                + " /awf multiprofile migrate-repository confirm with no player online, or move that directory away"
                + " to start empty";
            SourbyLogger.error("AWF MultiProfile: " + this.writesRefused + ". Every profile write is refused until then.");
        } else {
            SourbyLogger.warn("AWF MultiProfile: the file repository " + directory + " still holds profiles, but the Redis"
                + " repository is in use (" + (imported ? "imported" : commits + " commits, format " + this.marker.format()) + "); they are not read."
                + " Only one file repository is migrated, once, and the copy there is kept untouched");
        }
    }

    /**
     * What Redis reported at startup. Status output reads it; nothing is probed again.
     *
     * @param version {@code redis_version}
     * @param aof {@code aof_enabled:1}
     * @param appendfsync the fsync policy, or why it is unknown ({@code CONFIG GET} may be refused)
     * @param replicas {@code connected_slaves}
     * @param role {@code role}
     * @param cluster {@code cluster_enabled:1}
     */
    record Durability(String version, boolean aof, String appendfsync, int replicas, String role, boolean cluster) {

        static Durability probe(final RedisClient control) throws IOException {
            final List<Object> replies = control.pipeline(List.of(
                new Object[] {"INFO", "server"}, new Object[] {"INFO", "persistence"},
                new Object[] {"INFO", "replication"}, new Object[] {"INFO", "cluster"},
                new Object[] {"CONFIG", "GET", "appendfsync"}));
            final Map<String, String> info = new HashMap<>();
            for (int i = 0; i < 4; i++) {
                if (replies.get(i) instanceof RedisClient.RedisError error) {
                    throw new IOException("Redis refused INFO: " + error.getMessage());
                }
                final String text = RedisClient.text(replies.get(i));
                if (text == null) continue;
                for (final String line : text.split("\r?\n")) {
                    final int colon = line.indexOf(':');
                    if (colon > 0 && !line.startsWith("#")) info.put(line.substring(0, colon), line.substring(colon + 1).trim());
                }
            }
            String appendfsync = "unknown: CONFIG GET was refused";
            if (replies.get(4) instanceof List<?> config && config.size() >= 2) appendfsync = RedisClient.text(config.get(1));
            int replicas = 0;
            try {
                replicas = Integer.parseInt(info.getOrDefault("connected_slaves", "0"));
            } catch (final NumberFormatException unknown) {
                // Reported as none; the role check below still applies.
            }
            return new Durability(info.getOrDefault("redis_version", "unknown"), "1".equals(info.get("aof_enabled")),
                appendfsync, replicas, info.getOrDefault("role", "unknown"), "1".equals(info.get("cluster_enabled")));
        }

        /** The major version number, or -1 when {@code redis_version} is not numeric. */
        int majorVersion() {
            return versionPart(0);
        }

        /** The minor version number, or -1 when it cannot be read. */
        int minorVersion() {
            return versionPart(1);
        }

        private int versionPart(final int index) {
            final String[] parts = this.version.split("\\.");
            if (parts.length <= index) return -1;
            try {
                return Integer.parseInt(parts[index].trim());
            } catch (final NumberFormatException unknown) {
                return -1;
            }
        }

        /** Refuses a Redis the repository cannot run on safely, naming why. */
        void requireSupported(final boolean requireAof) {
            if (this.cluster) {
                throw new IllegalStateException("repository: redis does not support a Redis Cluster: one commit touches the"
                    + " session key and records of several profiles, which a cluster puts in different slots; use one primary");
            }
            if (!"master".equals(this.role)) {
                throw new IllegalStateException("repository: redis needs the Redis primary, but this Redis reports role "
                    + this.role + "; a replica refuses writes and can serve stale records");
            }
            int major = -1;
            try {
                final int dot = this.version.indexOf('.');
                major = Integer.parseInt(dot < 0 ? this.version : this.version.substring(0, dot));
            } catch (final NumberFormatException unknown) {
                // Refused below.
            }
            if (major < 7) {
                throw new IllegalStateException("repository: redis needs Redis 7.0 or newer (this Redis reports "
                    + this.version + "): its write scripts declare #!lua so that Redis refuses them up front when out"
                    + " of memory instead of applying half a commit");
            }
            if (requireAof && !this.aof) {
                throw new IllegalStateException("Redis has appendonly off, so a Redis restart would lose every profile"
                    + " written since its last RDB snapshot. Enable appendonly, or set"
                    + " aurora.awf.multiprofile.redis.require-aof: false to accept that");
            }
        }
    }

    // ------------------------------------------------------------------ keys and helpers

    private String meta() {
        return this.namespace + "meta";
    }

    private String accountKey(final UUID playerId) {
        return this.namespace + "account:" + playerId;
    }

    private String profileKey(final UUID profileId) {
        return this.namespace + "profile:" + profileId;
    }

    private String bindingKey(final UUID profileId) {
        return this.namespace + "binding:" + profileId;
    }

    private String snapsKey(final UUID profileId) {
        return this.namespace + "snaps:" + profileId;
    }

    private String snapIndexKey(final UUID profileId) {
        return this.namespace + "snapidx:" + profileId;
    }

    private String legacyKey(final UUID playerId) {
        return this.namespace + "legacy:" + playerId;
    }

    private String sessionMarkerKey(final UUID playerId) {
        return this.namespace + "session:" + playerId;
    }

    private String stageKey(final UUID snapshotId) {
        return this.namespace + "stage:" + snapshotId;
    }

    private String journalsKey(final UUID playerId) {
        return this.namespace + "journals:" + playerId;
    }

    private String journalMetaKey(final UUID playerId) {
        return this.namespace + "jmeta:" + playerId;
    }

    private String journalPlayerKey() {
        return this.namespace + "journal-player";
    }

    private String pendingKey() {
        return this.namespace + "journal-pending";
    }

    private String failedKey() {
        return this.namespace + "journal-failed";
    }

    private String namesKey(final UUID accountId) {
        return this.namespace + "names:" + accountId;
    }

    private String sharedKey(final UUID profileId) {
        return this.namespace + "shared:" + profileId;
    }

    private String headKey(final UUID profileId, final UUID memberId, final UUID epoch) {
        return this.namespace + "head:" + profileId + ":" + memberId + ":" + epoch;
    }

    private String memberSnapsKey(final UUID profileId, final UUID memberId, final UUID epoch) {
        return this.namespace + "msnaps:" + profileId + ":" + memberId + ":" + epoch;
    }

    private String memberSnapIndexKey(final UUID profileId, final UUID memberId, final UUID epoch) {
        return this.namespace + "msnapidx:" + profileId + ":" + memberId + ":" + epoch;
    }

    private String pinsKey(final UUID profileId, final UUID memberId, final UUID epoch) {
        return this.namespace + "pins:" + profileId + ":" + memberId + ":" + epoch;
    }

    private String intentKey(final UUID operationId) {
        return this.namespace + "intent:" + operationId;
    }

    private String blobsKey(final UUID operationId) {
        return this.namespace + "blobs:" + operationId;
    }

    private String blobStageKey(final BlobRef ref) {
        return this.namespace + "stage2:" + HEX.formatHex(ref.sha256());
    }

    private String receiptsKey(final UUID resource) {
        return receiptsKey(resource, false);
    }

    /** Receipts of the retained kinds, or (Build 54) the never-pruned permanent receipts of financial and delivery kinds. */
    private String receiptsKey(final UUID resource, final boolean permanent) {
        return this.namespace + (permanent ? "preceipts:" : "receipts:") + resource;
    }

    private String receiptDigestKey(final UUID resource, final boolean permanent) {
        return this.namespace + (permanent ? "prdigest:" : "rdigest:") + resource;
    }

    private String inviteKey(final UUID inviteId) {
        return this.namespace + "invite:" + inviteId;
    }

    private String voteKey(final UUID voteId) {
        return this.namespace + "vote:" + voteId;
    }

    private String salvageKey(final UUID salvageId) {
        return this.namespace + "salvage:" + salvageId;
    }

    private String indexGenerationKey() {
        return this.namespace + "idxgen";
    }

    private static String indexScope(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch) {
        return kind.tag() + ":" + resource + (member == null ? "" : ":" + member + ":" + epoch);
    }

    private String indexKey(final ProfileIndexPage.Kind kind, final UUID resource, final UUID member, final UUID epoch) {
        return this.namespace + "idx:" + indexScope(kind, resource, member, epoch);
    }

    /** The bulk-reply bound for a record of {@code type}: its payload limit plus the frame overhead. */
    private static int bound(final byte type) {
        return ProfileRecords.maxPayload(type) + ProfileRecords.OVERHEAD + 64;
    }

    private byte[] profileRecord(final AuroraProfile profile) throws IOException {
        return ProfileRecords.frame(ProfileRecords.TYPE_PROFILE, profile.revision(), ProfileRecords.profilePayload(profile),
            profileKey(profile.id()));
    }

    /** A record hash's frame (bounded read), checked against its {@code rev} field; {@code null} when there is none. */
    private ProfileRecords.Frame record(final String key, final byte type) throws IOException {
        ensureOpen();
        return frameOf(key, type, this.client.callBounded(bound(type), "HMGET", key, "rev", "rec"));
    }

    private static ProfileRecords.Frame frameOf(final String key, final byte type, final Object reply) throws IOException {
        if (reply instanceof RedisClient.RedisError error) throw new RedisClient.RedisError(error.getMessage());
        final List<?> fields = list(reply);
        if (fields.size() < 2 || !(fields.get(1) instanceof byte[] bytes)) return null;
        final ProfileRecords.Frame frame = ProfileRecords.unframe(bytes, type, key);
        final String revision = RedisClient.text(fields.get(0));
        if (!Long.toString(frame.revision()).equals(revision)) {
            throw new IOException("record " + key + " has revision field " + revision + " but its record says "
                + frame.revision());
        }
        return frame;
    }

    /**
     * A record's frame format must match the store: format 1 in a format-1 store, format 2 in a ready
     * format-2 store; while a migration is in progress both are read.
     */
    private void checkFrameFormat(final ProfileRecords.Frame frame, final String where) throws IOException {
        final StoreFormat.Marker marker = this.marker;
        if (marker.migration() == StoreFormat.MigrationState.IN_PROGRESS) return;
        final int expected = marker.legacyFormat() ? ProfileRecords.FORMAT : ProfileRecords.FORMAT_2;
        if (frame.format() != expected) {
            throw new IOException("record " + where + " is format " + frame.format() + " in a format-" + expected + " store; the store"
                + " needs the offline migration (or its backup restored) before this record is read");
        }
    }

    private PlayerAccount decodeAccount(final ProfileRecords.Frame frame, final UUID playerId, final String where) throws IOException {
        checkFrameFormat(frame, where);
        return frame.format() == ProfileRecords.FORMAT ? ProfileRecords.account(frame, playerId, where)
            : ProfileRecords.account2(frame, playerId, where);
    }

    private AuroraProfile decodeProfile(final ProfileRecords.Frame frame, final UUID profileId, final String where) throws IOException {
        checkFrameFormat(frame, where);
        return frame.format() == ProfileRecords.FORMAT ? ProfileRecords.profile(frame, profileId, where)
            : ProfileRecords.profile2(frame, profileId, where);
    }

    private ProfileSnapshot decodeLegacy(final byte[] bytes, final UUID playerId, final String where) throws IOException {
        final ProfileRecords.Frame frame = ProfileRecords.unframe(bytes, ProfileRecords.TYPE_SNAPSHOT, where);
        checkFrameFormat(frame, where);
        return frame.format() == ProfileRecords.FORMAT ? ProfileRecords.snapshot(frame, playerId, where)
            : ProfileRecords.legacyArchive2(frame, playerId, where);
    }

    /** Journals decode by their own frame format: a terminal format-1 journal left in a migrated store stays readable. */
    private static ProfileTransition journal(final String key, final String transitionId, final byte[] bytes)
        throws IOException {
        final String where = key + " " + transitionId;
        final ProfileRecords.Frame frame = ProfileRecords.unframe(bytes, ProfileRecords.TYPE_TRANSITION, where);
        final ProfileTransition journal = frame.format() == ProfileRecords.FORMAT ? ProfileRecords.transition(frame, where)
            : ProfileRecords.transition2(frame, where);
        if (!journal.transitionId().toString().equals(transitionId)) {
            throw new IOException("transition journal " + where + " holds " + journal.transitionId());
        }
        return journal;
    }

    private static String expected(final ProfileWriteGuard guard) {
        return guard.expectedRevision() == ProfileWriteGuard.ANY ? "any" : Long.toString(guard.expectedRevision());
    }

    private static String idOrDash(final UUID id) {
        return id == null ? "-" : id.toString();
    }

    private static UUID uuid(final String text, final String where) throws IOException {
        try {
            return UUID.fromString(text);
        } catch (final IllegalArgumentException | NullPointerException malformed) {
            throw new IOException(where + " holds '" + text + "', not a UUID");
        }
    }

    private static List<?> list(final Object reply) {
        return reply instanceof List<?> values ? values : List.of();
    }

    private static long number(final Object reply) {
        if (reply instanceof Long value) return value;
        final String text = RedisClient.text(reply);
        if (text == null) return 0L;
        try {
            return Long.parseLong(text);
        } catch (final NumberFormatException notANumber) {
            return 0L;
        }
    }
}
