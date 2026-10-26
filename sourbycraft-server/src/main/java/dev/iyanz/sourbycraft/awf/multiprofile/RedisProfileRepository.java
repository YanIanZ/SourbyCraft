package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload;
import dev.iyanz.sourbycraft.awf.redis.fabric.SessionFabric;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p><b>Snapshots</b> (up to 8 MiB) are first written alone under a unique staging key with a TTL; the
 * publish script moves the staged record into the profile's snapshot hash and advances the profile record
 * in the same atomic step. A crash in between leaves an orphan that expires, never a half-published
 * snapshot. Retention prunes inside that script and keeps every snapshot a pending journal of the
 * account names.</p>
 *
 * <p><b>Records</b> are the framed, checksummed records of {@link FileProfileRepository}
 * ({@link ProfileRecords}), with the same size bounds, verified on every read.</p>
 *
 * <p><b>Keys</b>, under the fabric namespace plus {@code mp:}: {@code meta}, {@code account:<player>},
 * {@code profile:<profile>}, {@code binding:<profile>}, {@code snaps:<profile>} (snapshot id to record),
 * {@code snapidx:<profile>} (snapshot ids by revision), {@code legacy:<player>}, {@code stage:<snapshot>},
 * {@code journals:<player>}, {@code jmeta:<player>}, {@code journal-player}, {@code journal-pending} and
 * {@code journal-failed}; session keys are {@link SessionFabric}'s. Every key a script touches is passed
 * to it as a key. One commit touches keys of several profiles and a session, which a Redis Cluster puts in
 * different slots, so a cluster is refused at startup.</p>
 *
 * <p><b>Durability is Redis's.</b> {@link #open} reads {@code INFO} and refuses a replica, a cluster,
 * Redis before 7.0 and, while {@code redis.require-aof} is true, {@code appendonly no}. With
 * {@code appendfsync everysec} up to about one second of acknowledged writes can be lost when Redis
 * crashes, and an asynchronous replica promoted on failover can lose acknowledged writes and move session
 * epochs back: one primary is the supported topology. With {@code redis.wait-for-aof} the writes a later
 * step relies on (snapshots, accounts, profiles, bindings, and the journal written before a target state
 * is applied) are followed by {@code WAITAOF 1 0} on the same connection; a write Redis does not confirm
 * in time is still applied, and is logged and counted. Other journal writes and deletions are acknowledged
 * from memory: losing them in a crash can only make a journal look earlier or reappear, which recovery
 * resolves.</p>
 *
 * <p>Every method blocks on Redis and must run on an I/O worker. The repository uses the fabric's STORAGE
 * pool and never closes it.</p>
 */
public final class RedisProfileRepository implements ProfileRepository {

    /** The repository layout this build reads and writes. */
    private static final String FORMAT = "1";
    /** How long a staged snapshot waits for its commit before Redis drops it. */
    private static final long STAGE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** Journals one player may have; far above the few pending plus the terminal ones kept. */
    private static final int MAX_JOURNALS_PER_PLAYER = 64;
    /** Pending journals listed for status output. */
    private static final int MAX_LISTED_PENDING = 1024;
    /** Failed journals indexed for status output; older ones stay readable by player. */
    private static final int FAILED_LISTED = 64;
    private static final long UNCONFIRMED_WARN_NANOS = TimeUnit.MINUTES.toNanos(1);

    /**
     * Shared by every write script. KEYS[1] is the meta hash; KEYS[2] is the session key of the writer's
     * claim (the meta hash again for a write without one). ARGV[1] is the claim's session field ('' when
     * there is no claim), ARGV[2] its owner token and ARGV[3] its epoch.
     */
    private static final String HEADER = """
        #!lua
        local function fence()
          if ARGV[1] == '' then return nil end
          local cur = redis.call('HMGET', KEYS[2], 'session', 'owner', 'epoch', 'expiresAt')
          if cur[1] ~= ARGV[1] or cur[2] ~= ARGV[2] or tonumber(cur[3] or '0') ~= tonumber(ARGV[3]) then
            return {'FENCED', 'superseded', tostring(cur[3] or '0')}
          end
          local now = redis.call('TIME')
          if (tonumber(cur[4] or '0') or 0) <= tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) then
            return {'FENCED', 'expired', tostring(cur[3])}
          end
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
        local refused = fence() or stale(KEYS[3], ARGV[4], tonumber(ARGV[5]))
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
        local refused = fence() or stale(KEYS[3], ARGV[4], -1)
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

    /** KEYS[3] legacy hash of the player, [4] staged snapshot. ARGV[4] snapshot id. */
    private static final String SAVE_LEGACY = HEADER + """
        local refused = fence()
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
     * kept, [13] journals a player may have, [14] failed journals indexed.
     */
    private static final String SAVE_JOURNAL = HEADER + """
        local refused = fence()
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

    /** KEYS as {@link #SAVE_JOURNAL}. ARGV[4] transition id, [5] player. */
    private static final String DELETE_JOURNAL = HEADER + """
        local refused = fence()
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
        local refused = fence()
        if refused then return refused end
        redis.call('DEL', KEYS[3], KEYS[4], KEYS[5])
        redis.call('HDEL', KEYS[6], 'latest')
        committed()
        return {'OK'}
        """;

    /** KEYS[1] meta. ARGV[1] start time. Refused once anything committed or an import finished. */
    private static final String IMPORT_BEGIN = """
        #!lua
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
    private final String namespace;
    private final Durability durability;
    private volatile boolean waitForAof;
    private final AtomicLong unconfirmed = new AtomicLong();
    private final AtomicLong lastUnconfirmedWarn = new AtomicLong(System.nanoTime() - UNCONFIRMED_WARN_NANOS);
    /** Why every write is refused: unmigrated profiles in the file repository. {@code null} when writable. */
    private volatile String writesRefused;
    /** Journals the last {@link #transitions()} could not read, for status output. */
    private volatile List<String> unreadable = List.of();
    private volatile boolean closed;

    private RedisProfileRepository(final RedisClient client, final SessionFabric sessions, final String namespace,
                                   final Durability durability, final boolean waitForAof) {
        this.client = client;
        this.sessions = sessions;
        this.namespace = namespace;
        this.durability = durability;
        this.waitForAof = waitForAof;
    }

    /**
     * Opens the repository of a running fabric. Refuses (with the reason) a Redis it cannot run on: a
     * replica, a cluster, a version before 7.0, {@code appendonly no} while {@code require-aof} is
     * true, or a repository written by a newer build. When the repository is still empty and the file
     * repository in {@code fileDirectory} holds profiles, writes are refused until
     * {@link ProfileRepositoryMigration} copied them, so no profile is created over data that was not
     * migrated yet.
     *
     * @throws IOException when Redis cannot be read
     * @throws IllegalStateException when this Redis or repository is refused
     */
    public static RedisProfileRepository open(final RedisFabric fabric, final ProfileSettings.Repository settings,
                                              final Path fileDirectory) throws IOException {
        final Durability durability = Durability.probe(fabric.client(RedisWorkload.CONTROL));
        durability.requireSupported(settings.requireAof());
        if (!durability.aof()) {
            SourbyLogger.warn("AWF MultiProfile: Redis has appendonly off and redis.require-aof is false; profiles are"
                + " as durable as Redis's RDB snapshots, and a Redis restart loses everything written since the last one");
        }
        if (durability.replicas() > 0) {
            SourbyLogger.warn("AWF MultiProfile: Redis has " + durability.replicas() + " replica(s). Replication is"
                + " asynchronous: a failover to a replica can lose acknowledged profile writes and move session epochs"
                + " back. One primary is the supported topology for repository: redis");
        }
        final RedisProfileRepository repository = new RedisProfileRepository(fabric.storageClient(), fabric.sessions(),
            fabric.prefix() + "mp:", durability, settings.waitForAof());
        repository.checkFormat();
        repository.checkUnmigrated(fileDirectory.toAbsolutePath().normalize());
        return repository;
    }

    // ------------------------------------------------------------------ status

    @Override
    public String describe() {
        final Durability durability = this.durability;
        final long unconfirmed = this.unconfirmed.get();
        final String refused = this.writesRefused;
        return "redis " + this.namespace + "*, Redis " + durability.version()
            + (durability.replicas() > 0 ? " with " + durability.replicas() + " async replica(s)" : "")
            + ", " + (durability.aof() ? "AOF on (appendfsync " + durability.appendfsync() + ")" : "AOF OFF")
            + ", WAITAOF " + (this.waitForAof ? "on" : "off")
            + (unconfirmed > 0 ? ", " + unconfirmed + " write(s) not confirmed on disk in time" : "")
            + (refused == null ? "" : "; WRITES REFUSED: " + refused);
    }

    /** A failed write may have run in Redis with its reply lost; see {@link ProfileRepository#remote()}. */
    @Override
    public boolean remote() {
        return true;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PlayerAccount loadAccount(final UUID playerId, final int defaultSlots) throws IOException {
        final String key = accountKey(playerId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_ACCOUNT);
        if (frame == null) return PlayerAccount.empty(playerId, defaultSlots);
        return ProfileRecords.account(frame, playerId, key);
    }

    @Override
    public Optional<AuroraProfile> profile(final UUID profileId) throws IOException {
        final String key = profileKey(profileId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_PROFILE);
        return frame == null ? Optional.empty() : Optional.of(ProfileRecords.profile(frame, profileId, key));
    }

    @Override
    public List<AuroraProfile> profiles(final UUID accountId) throws IOException {
        final PlayerAccount account = loadAccount(accountId, PlayerAccount.DEFAULT_SLOTS);
        if (account.profileIds().isEmpty()) return List.of();
        final List<Object[]> commands = new ArrayList<>(account.profileIds().size());
        for (final UUID id : account.profileIds()) commands.add(new Object[] {"HMGET", profileKey(id), "rev", "rec"});
        ensureOpen();
        final List<Object> replies = this.client.pipeline(commands);
        final List<AuroraProfile> found = new ArrayList<>(replies.size());
        for (int i = 0; i < replies.size(); i++) {
            final UUID id = account.profileIds().get(i);
            final String key = profileKey(id);
            final ProfileRecords.Frame frame = frameOf(key, ProfileRecords.TYPE_PROFILE, replies.get(i));
            if (frame != null) found.add(ProfileRecords.profile(frame, id, key));
        }
        return List.copyOf(found);
    }

    @Override
    public Optional<ProfileWorldBinding> binding(final UUID profileId) throws IOException {
        final String key = bindingKey(profileId);
        final ProfileRecords.Frame frame = record(key, ProfileRecords.TYPE_BINDING);
        return frame == null ? Optional.empty() : Optional.of(ProfileRecords.binding(frame, key));
    }

    /**
     * The snapshot the profile record names as its latest, which the publish script sets in the step
     * that advances the revision. A record that names a snapshot which is gone fails instead of falling
     * back to an older one.
     */
    @Override
    public Optional<ProfileSnapshot> latestSnapshot(final UUID profileId) throws IOException {
        ensureOpen();
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
        final String where = snapsKey(profileId) + " " + snapshotId;
        final Object stored = this.client.call("HGET", snapsKey(profileId), snapshotId.toString());
        if (!(stored instanceof byte[] bytes)) return Optional.empty();
        final ProfileSnapshot snapshot = ProfileRecords.snapshot(
            ProfileRecords.unframe(bytes, ProfileRecords.TYPE_SNAPSHOT, where), profileId, where);
        if (!snapshot.snapshotId().equals(snapshotId)) throw new IOException("snapshot " + where + " holds " + snapshot.snapshotId());
        return Optional.of(snapshot);
    }

    @Override
    public Optional<ProfileSnapshot> legacySnapshot(final UUID playerId) throws IOException {
        ensureOpen();
        final String key = legacyKey(playerId);
        final String id = RedisClient.text(this.client.call("HGET", key, "latest"));
        if (id == null) return Optional.empty();
        final Object stored = this.client.call("HGET", key, id);
        if (!(stored instanceof byte[] bytes)) throw new IOException("the archive " + id + " named by " + key + " is missing");
        final String where = key + " " + id;
        return Optional.of(ProfileRecords.snapshot(ProfileRecords.unframe(bytes, ProfileRecords.TYPE_SNAPSHOT, where),
            playerId, where));
    }

    @Override
    public Optional<ProfileTransition> transition(final UUID transitionId) throws IOException {
        ensureOpen();
        final String player = RedisClient.text(this.client.call("HGET", journalPlayerKey(), transitionId.toString()));
        if (player == null) return Optional.empty();
        final String key = journalsKey(uuid(player, journalPlayerKey() + " " + transitionId));
        final Object stored = this.client.call("HGET", key, transitionId.toString());
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
        final List<?> flat = list(this.client.call("HGETALL", key));
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

    // ------------------------------------------------------------------ writes

    @Override
    public void saveAccount(final PlayerAccount account, final ProfileWriteGuard guard) throws IOException {
        final String key = accountKey(account.playerId());
        commit("account " + account.playerId(), SAVE_RECORD, guard, Ack.AOF, List.of(key), List.of(expected(guard), "0",
            Long.toString(account.revision()),
            ProfileRecords.frame(ProfileRecords.TYPE_ACCOUNT, account.revision(), ProfileRecords.accountPayload(account), key)));
    }

    @Override
    public void saveProfile(final AuroraProfile profile, final ProfileWriteGuard guard) throws IOException {
        final String key = profileKey(profile.id());
        commit("profile " + profile.id(), SAVE_RECORD, guard, Ack.AOF, List.of(key), List.of(expected(guard),
            Long.toString(ProfileWriteGuard.ABSENT), Long.toString(profile.revision()), profileRecord(profile)));
    }

    /** Bindings carry no revision; the guard's claim is checked, its revision is not (as in the file repository). */
    @Override
    public void saveBinding(final ProfileWorldBinding binding, final ProfileWriteGuard guard) throws IOException {
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
        ensureWritable();
        final String stage = stageKey(snapshot.snapshotId());
        stage(stage, snapshot);
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

    @Override
    public void saveLegacySnapshot(final UUID playerId, final ProfileSnapshot snapshot, final ProfileWriteGuard guard)
        throws IOException {
        if (!snapshot.profileId().equals(playerId)) {
            throw new IllegalArgumentException("legacy snapshot of " + snapshot.profileId() + " saved for " + playerId);
        }
        ensureWritable();
        final String stage = stageKey(snapshot.snapshotId());
        stage(stage, snapshot);
        try {
            commit("the pre-profile archive of " + playerId, SAVE_LEGACY, guard, Ack.AOF, List.of(legacyKey(playerId), stage),
                List.of(snapshot.snapshotId().toString()));
        } catch (final IOException | RuntimeException failed) {
            discard(stage);
            throw failed;
        }
    }

    /**
     * Writes a journal. The phase written before a target state is applied waits for the append-only
     * file (with {@code wait-for-aof}): recovery must find it after a Redis crash, or it would treat an
     * applied target as never applied. Other phases are acknowledged from memory.
     */
    @Override
    public void saveTransition(final ProfileTransition transition, final ProfileWriteGuard guard) throws IOException {
        final UUID playerId = transition.playerId();
        final String id = transition.transitionId().toString();
        final String key = journalsKey(playerId);
        commit("transition journal " + id, SAVE_JOURNAL, guard,
            transition.phase() == TransitionPhase.APPLYING_TARGET ? Ack.AOF : Ack.MEMORY,
            List.of(key, journalMetaKey(playerId), journalPlayerKey(), pendingKey(), failedKey()),
            List.of(id, playerId.toString(),
                ProfileRecords.frame(ProfileRecords.TYPE_TRANSITION, 1L, ProfileRecords.transitionPayload(transition),
                    key + " " + id),
                transition.terminal() ? "1" : "0", Long.toString(transition.updatedAtMillis()),
                idOrDash(transition.fromSnapshotId()), idOrDash(transition.toSnapshotId()),
                transition.phase() == TransitionPhase.FAILED ? "1" : "0",
                Integer.toString(FileProfileRepository.TERMINAL_JOURNALS_KEPT), Integer.toString(MAX_JOURNALS_PER_PLAYER),
                Integer.toString(FAILED_LISTED)));
    }

    @Override
    public void deleteTransition(final UUID transitionId, final ProfileWriteGuard guard) throws IOException {
        ensureWritable();
        final String player = RedisClient.text(this.client.call("HGET", journalPlayerKey(), transitionId.toString()));
        if (player == null) return;
        final UUID playerId = uuid(player, journalPlayerKey() + " " + transitionId);
        commit("transition journal " + transitionId, DELETE_JOURNAL, guard, Ack.MEMORY,
            List.of(journalsKey(playerId), journalMetaKey(playerId), journalPlayerKey(), pendingKey(), failedKey()),
            List.of(transitionId.toString(), player));
    }

    @Override
    public void deleteProfileData(final UUID profileId, final ProfileWriteGuard guard) throws IOException {
        commit("the data of profile " + profileId, DELETE_PROFILE_DATA, guard, Ack.MEMORY,
            List.of(bindingKey(profileId), snapsKey(profileId), snapIndexKey(profileId), profileKey(profileId)), List.of());
    }

    /** Stops accepting calls; the fabric owns the connection pool and closes it. */
    @Override
    public void close() {
        this.closed = true;
    }

    // ------------------------------------------------------------------ import, for ProfileRepositoryMigration

    /** Starts the one-way import; refused once anything has committed here or an import finished. */
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
        stage(stage, snapshot);
        try {
            expectOk("importing snapshot " + snapshot.snapshotId(), this.client.eval(IMPORT_BLOB, 5, meta(), stage, hash,
                latestHash, index, snapshot.snapshotId().toString(), revision, latest ? "1" : "0"));
        } catch (final IOException | RuntimeException failed) {
            discard(stage);
            throw failed;
        }
    }

    // ------------------------------------------------------------------ commit plumbing

    /**
     * Runs one write script: KEYS[1] meta, KEYS[2] the claim's session key (meta without a claim), then
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
        call.add(claim == null ? meta() : sessionKey(claim));
        call.addAll(keys);
        if (claim == null) {
            call.add("");
            call.add("");
            call.add("0");
        } else {
            call.add(this.sessions.session(claim.node(), UUID.fromString(claim.owner())));
            call.add(claim.owner());
            call.add(Long.toString(claim.fence()));
        }
        call.addAll(args);
        final Object[] keysAndArgs = call.toArray();
        if (ack == Ack.AOF && this.waitForAof) {
            final int waitMillis = Math.max(1, this.client.remainingTimeoutMillis() / 2);
            final List<Object> replies = evalThenWait(script, keysAndArgs, waitMillis);
            interpret(what, guard, replies.get(0));
            confirm(replies.get(1), waitMillis);
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
        final Object[] wait = {"WAITAOF", "1", "0", Integer.toString(waitMillis)};
        List<Object> replies = this.client.pipeline(List.of(command("EVALSHA", RedisClient.sha1(script), keysAndArgs), wait));
        if (replies.get(0) instanceof RedisClient.RedisError missing && missing.getMessage() != null
            && missing.getMessage().startsWith("NOSCRIPT")) {
            // The script did not run, so the first WAITAOF waited for nothing of it.
            replies = this.client.pipeline(List.of(command("EVAL", script, keysAndArgs), wait));
        }
        if (replies.get(0) instanceof RedisClient.RedisError error) throw new RedisClient.RedisError(error.getMessage());
        return replies;
    }

    private static Object[] command(final String name, final String script, final Object[] keysAndArgs) {
        final Object[] command = new Object[keysAndArgs.length + 2];
        command[0] = name;
        command[1] = script;
        System.arraycopy(keysAndArgs, 0, command, 2, keysAndArgs.length);
        return command;
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
                    + (claim == null ? "the profile" : "profile " + claim.id() + " (epoch " + claim.fence() + ")")
                    + ("expired".equals(detail) ? " has expired" : " no longer holds it (Redis has epoch " + epoch + ")")
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
            case "MISSING" -> throw new IOException(what + ": " + detail + " expired before its commit; nothing was written");
            case "REFUSED" -> throw new IOException(what + " was refused: " + detail);
            default -> throw new IOException(what + ": unexpected reply " + status);
        }
    }

    /** The {@code WAITAOF} reply of an applied write: confirmed, unconfirmed in time, or unsupported. */
    private void confirm(final Object reply, final int waitMillis) {
        if (reply instanceof RedisClient.RedisError unsupported) {
            this.waitForAof = false;
            SourbyLogger.warn("AWF MultiProfile: Redis cannot confirm writes to its append-only file ("
                + unsupported.getMessage() + "); profile writes are acknowledged from Redis's memory from now on."
                + " Enable appendonly on Redis 7.2+, or set aurora.awf.multiprofile.redis.wait-for-aof: false");
            return;
        }
        if (reply instanceof List<?> counts && !counts.isEmpty() && counts.getFirst() instanceof Long local && local >= 1L) {
            return;
        }
        final long total = this.unconfirmed.incrementAndGet();
        final long now = System.nanoTime();
        final long last = this.lastUnconfirmedWarn.get();
        if (now - last >= UNCONFIRMED_WARN_NANOS && this.lastUnconfirmedWarn.compareAndSet(last, now)) {
            SourbyLogger.warn("AWF MultiProfile: Redis applied a profile write but did not confirm it in its append-only"
                + " file within " + waitMillis + " ms (" + total + " such write(s) so far); it is lost if Redis crashes"
                + " before its next fsync");
        }
    }

    private static void expectOk(final String what, final Object reply) throws IOException {
        final List<?> fields = list(reply);
        final String status = fields.isEmpty() ? null : RedisClient.text(fields.get(0));
        if ("OK".equals(status)) return;
        throw new IOException(what + " was refused: " + (fields.size() > 1 ? RedisClient.text(fields.get(1)) : status));
    }

    /** Writes a snapshot record alone under its staging key; the commit moves it or it expires. */
    private void stage(final String key, final ProfileSnapshot snapshot) throws IOException {
        final byte[] record = ProfileRecords.frame(ProfileRecords.TYPE_SNAPSHOT, snapshot.revision(),
            ProfileRecords.snapshotPayload(snapshot), key);
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

    private String sessionKey(final OwnerClaim claim) {
        if (!"session".equals(claim.kind())) {
            throw new IllegalArgumentException("the Redis profile repository is fenced by session fabric claims, not "
                + claim.kind() + " claims");
        }
        return this.sessions.key(UUID.fromString(claim.id()));
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

    private void checkFormat() throws IOException {
        final String stored = RedisClient.text(this.client.call("HGET", meta(), "format"));
        if (stored == null) {
            this.client.call("HSETNX", meta(), "format", FORMAT);
        } else if (!FORMAT.equals(stored)) {
            throw new IllegalStateException("the Redis profile repository " + meta() + " has format " + stored
                + "; this build reads format " + FORMAT);
        }
    }

    /**
     * Refuses writes while this repository is empty and the file repository still holds profiles: a
     * profile created now would make the migration impossible (it copies only into an empty repository).
     * When this repository is in use already, profiles left in the file repository are reported, never
     * read.
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
        if (commits == 0L && !imported) {
            this.writesRefused = "the file repository " + directory + " holds profiles that are not in Redis yet; run"
                + " /awf multiprofile migrate-repository confirm with no player online, or move that directory away"
                + " to start empty";
            SourbyLogger.error("AWF MultiProfile: " + this.writesRefused + ". Every profile write is refused until then.");
        } else {
            SourbyLogger.warn("AWF MultiProfile: the file repository " + directory + " still holds profiles, but the Redis"
                + " repository is in use (" + (imported ? "imported" : commits + " commits") + "); they are not read."
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

    private byte[] profileRecord(final AuroraProfile profile) throws IOException {
        return ProfileRecords.frame(ProfileRecords.TYPE_PROFILE, profile.revision(), ProfileRecords.profilePayload(profile),
            profileKey(profile.id()));
    }

    /** A record hash's frame, checked against its {@code rev} field; {@code null} when there is none. */
    private ProfileRecords.Frame record(final String key, final byte type) throws IOException {
        ensureOpen();
        return frameOf(key, type, this.client.call("HMGET", key, "rev", "rec"));
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

    private static ProfileTransition journal(final String key, final String transitionId, final byte[] bytes)
        throws IOException {
        final String where = key + " " + transitionId;
        final ProfileTransition journal = ProfileRecords.transition(
            ProfileRecords.unframe(bytes, ProfileRecords.TYPE_TRANSITION, where), where);
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
        } catch (final IllegalArgumentException malformed) {
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
