package dev.iyanz.sourbycraft.awf.multiprofile;

/**
 * The Lua of the format-2 (co-op) write scripts of {@link RedisProfileRepository}.
 *
 * <p>Every script starts with {@link #HEADER}, which fixes the first six keys and thirteen arguments
 * and the version vector that follows them, then consumes its own keys and arguments <em>sequentially</em>
 * through {@code nk()} / {@code na()} so that a Java builder and the script cannot disagree on an index.
 * Every script validates everything (store gate, replay, claims, revisions, predicates, staged data,
 * limits) before its first write, because a Lua runtime error does not roll back the Redis commands that
 * ran before it. Replies are string arrays whose first element is the status the repository maps:</p>
 * <ul>
 *   <li>{@code OK} applied; {@code REPLAYED <receipt>} the operation id was decided already (same digest);
 *       {@code INTENT <phaseTag> <intentRecord>} the intent exists (prepare replay, or a pending resolve);</li>
 *   <li>{@code LEGACY} format-1 store; {@code FORMAT <why>} unknown format, newer schema or migration in
 *       progress; {@code DIGEST} same operation id with another request digest;</li>
 *   <li>{@code FENCED <superseded|expired> <storedFence> <P|Q|Q2>}; {@code STALE <storedRevision> <vectorIndex>};
 *       {@code ACTIVE <storedActiveOrDash>}; {@code RESERVED <operationId>} a record carries another
 *       operation's reservation; {@code PENDING <storedOrDash> <writtenOrDash>} a direct commit would install
 *       or clear a reservation; {@code NAME <holderProfileId>}; {@code LIMIT <what>}; {@code MISSING <what>};
 *       {@code DECIDED <phaseTag>} abort after commit; {@code NOINTENT}; {@code REFUSED <why>}.</li>
 * </ul>
 *
 * <p>The header also fixes {@code READS_UP_TO_BUILD} ({@link StoreFormat#READS_UP_TO_BUILD}) as a Lua constant: the
 * gate refuses a store whose {@code minimumBuild} is above it, as it refuses a newer payload schema, so a node of an
 * older build cannot write to a store a newer one raised.</p>
 *
 * <p>Fixed layout. KEYS: [1] meta, [2] {@code P} claim hash (meta when none), [3] {@code Q} actor claim
 * hash, [4] {@code Q} affected claim hash, [5] receipts hash of the resource, [6] receipt-digest hash of
 * the resource, then the version vector's record keys. ARGV: [1] {@code '1'} when the writer is the
 * offline migration (a store whose migration is in progress accepts only those), [2] the highest schema
 * this build reads, [3] operation id, [4] request digest (hex), [5..6] P session and fence ({@code ''}
 * and {@code 0} when none), [7..8] Q actor, [9..10] Q affected, [11] receipt record to store ({@code ''}
 * for none), [12] receipt cap per resource, [13] vector length {@code n}, then {@code n} pairs
 * (expected revision, revision of a missing record). Counters are compared with {@code tonumber};
 * every counter the scripts write is validated against {@code 2^53 - 1}, so FILE and Redis agree.</p>
 */
final class RedisProfileScripts {

    private RedisProfileScripts() {}

    static final String HEADER = "#!lua\nlocal READS_UP_TO_BUILD = " + StoreFormat.READS_UP_TO_BUILD + "\n" + """
        local MAXC = 9007199254740991
        local function now()
          local t = redis.call('TIME')
          return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        end
        local function gate()
          local m = redis.call('HMGET', KEYS[1], 'format', 'migrationState', 'schema', 'minimumBuild')
          local format = m[1] or '1'
          if format == '1' then return {'LEGACY'} end
          if format ~= '2' then return {'FORMAT', 'the store is format ' .. format} end
          local state = m[2] or '1'
          if state == '2' then
            if ARGV[1] ~= '1' then return {'FORMAT', 'a format-2 migration is in progress'} end
          elseif state ~= '1' and state ~= '3' then
            return {'FORMAT', 'migration state ' .. state .. ' is not known to this build'}
          end
          if (tonumber(m[3] or '0') or 0) > tonumber(ARGV[2]) then
            return {'FORMAT', 'the store uses payload schema ' .. tostring(m[3])}
          end
          if (tonumber(m[4] or '0') or 0) > READS_UP_TO_BUILD then
            return {'FORMAT', 'the store requires Build ' .. tostring(m[4]) .. ' or newer; this build reads stores up to Build ' .. READS_UP_TO_BUILD}
          end
          return nil
        end
        local function fence(key, session, expected, name)
          if session == '' then return nil end
          local cur = redis.call('HMGET', key, 'session', 'fence', 'expiresAt')
          if cur[1] ~= session or tonumber(cur[2] or '0') ~= tonumber(expected) then
            return {'FENCED', 'superseded', tostring(cur[2] or '0'), name}
          end
          if (tonumber(cur[3] or '0') or 0) <= now() then return {'FENCED', 'expired', tostring(cur[2] or '0'), name} end
          return nil
        end
        local function claims()
          return fence(KEYS[2], ARGV[5], ARGV[6], 'P') or fence(KEYS[3], ARGV[7], ARGV[8], 'Q')
            or fence(KEYS[4], ARGV[9], ARGV[10], 'Q2')
        end
        local function replay()
          local d = redis.call('HGET', KEYS[6], ARGV[3])
          if d then
            if d ~= ARGV[4] then return {'DIGEST'} end
            return {'REPLAYED', redis.call('HGET', KEYS[5], ARGV[3]) or ''}
          end
          return nil
        end
        local function room()
          if ARGV[11] == '' then return nil end
          if redis.call('HLEN', KEYS[6]) >= tonumber(ARGV[12]) then return {'LIMIT', 'permanent receipts of this resource'} end
          return nil
        end
        local function stale(key, expected, absent)
          local stored = redis.call('HGET', key, 'rev')
          local revision = absent
          if stored then revision = tonumber(stored) end
          if revision ~= tonumber(expected) then return {'STALE', tostring(revision)} end
          return nil
        end
        local n = tonumber(ARGV[13])
        local k = 6 + n
        local a = 13 + 2 * n
        local function nk() k = k + 1; return KEYS[k] end
        local function na() a = a + 1; return ARGV[a] end
        local function vector()
          for i = 1, n do
            local r = stale(KEYS[6 + i], ARGV[12 + 2 * i], tonumber(ARGV[13 + 2 * i]))
            if r then r[3] = tostring(i); return r end
          end
          return nil
        end
        local function counter(v)
          local x = tonumber(v)
          return x ~= nil and x >= 0 and x <= MAXC and x == math.floor(x)
        end
        local function reserved(key)
          local p = redis.call('HGET', key, 'pending')
          if p and p ~= '' and p ~= ARGV[3] then return {'RESERVED', p} end
          return nil
        end
        local function keep(key, pending, allowed)
          local p = redis.call('HGET', key, 'pending') or ''
          if p ~= '' and not string.find(',' .. allowed .. ',', ',' .. p .. ',', 1, true) then return {'RESERVED', p} end
          if pending ~= p then return {'PENDING', p == '' and '-' or p, pending == '' and '-' or pending} end
          return nil
        end
        local function finish()
          if ARGV[11] ~= '' then
            redis.call('HSET', KEYS[5], ARGV[3], ARGV[11])
            redis.call('HSET', KEYS[6], ARGV[3], ARGV[4])
          end
          redis.call('HINCRBY', KEYS[1], 'commits', 1)
        end
        local function admit()
          return gate() or replay() or claims() or vector() or room()
        end
        """;

    /**
     * Keys: account hash. Args: new revision, record, active profile ('' none), pending ('' none), expected
     * active ('' no check, '-' none), the guard's reservations (comma-separated operation ids). Like every
     * direct script it refuses a record reserved by an operation outside those reservations and any change
     * to the stored reservation: intents install and clear reservations.
     */
    static final String COMMIT_ACCOUNT = HEADER + """
        local refused = admit()
        if refused then return refused end
        local hash = nk()
        local rev = na()
        local rec = na()
        local active = na()
        local pending = na()
        local expectActive = na()
        local allowed = na()
        if not counter(rev) then return {'REFUSED', 'revision ' .. rev .. ' is outside 0..2^53-1'} end
        refused = keep(hash, pending, allowed)
        if refused then return refused end
        if expectActive ~= '' then
          local cur = redis.call('HGET', hash, 'active')
          if not cur or cur == '' then cur = '-' end
          if cur ~= expectActive then return {'ACTIVE', cur} end
        end
        redis.call('HSET', hash, 'rev', rev, 'rec', rec, 'active', active, 'pending', pending)
        finish()
        return {'OK'}
        """;

    /** Keys: profile hash, names hash of the owner account. Args: new revision, record, name key, pending, profile id, '1' when deleted, account id, reservations. */
    static final String COMMIT_PROFILE = HEADER + """
        local refused = admit()
        if refused then return refused end
        local hash = nk()
        local names = nk()
        local rev = na()
        local rec = na()
        local nameKey = na()
        local pending = na()
        local profileId = na()
        local deleted = na()
        local account = na()
        local allowed = na()
        if not counter(rev) then return {'REFUSED', 'revision ' .. rev .. ' is outside 0..2^53-1'} end
        refused = keep(hash, pending, allowed)
        if refused then return refused end
        local holder = redis.call('HGET', names, nameKey)
        if holder and holder ~= profileId then return {'NAME', holder} end
        local old = redis.call('HGET', hash, 'name')
        if old and old ~= nameKey then return {'REFUSED', 'the name of a profile is immutable (' .. old .. ' -> ' .. nameKey .. ')'} end
        if deleted == '1' then
          if redis.call('HGET', names, nameKey) == profileId then redis.call('HDEL', names, nameKey) end
        else
          redis.call('HSET', names, nameKey, profileId)
        end
        redis.call('HSET', hash, 'rev', rev, 'rec', rec, 'name', nameKey, 'pending', pending, 'account', account)
        finish()
        return {'OK'}
        """;

    /** Keys: record hash. Args: new revision, record, pending, reservations. */
    static final String COMMIT_RECORD = HEADER + """
        local refused = admit()
        if refused then return refused end
        local hash = nk()
        local rev = na()
        local rec = na()
        local pending = na()
        local allowed = na()
        if not counter(rev) then return {'REFUSED', 'revision ' .. rev .. ' is outside 0..2^53-1'} end
        refused = keep(hash, pending, allowed)
        if refused then return refused end
        redis.call('HSET', hash, 'rev', rev, 'rec', rec, 'pending', pending)
        finish()
        return {'OK'}
        """;

    /** Keys: shared hash, actor account hash. Args: new revision, record, pending, expected active ('' no check), reservations. */
    static final String MUTATE_SHARED = HEADER + """
        local refused = admit()
        if refused then return refused end
        local hash = nk()
        local account = nk()
        local rev = na()
        local rec = na()
        local pending = na()
        local expectActive = na()
        local allowed = na()
        if not counter(rev) then return {'REFUSED', 'revision ' .. rev .. ' is outside 0..2^53-1'} end
        refused = keep(hash, pending, allowed)
        if refused then return refused end
        if expectActive ~= '' then
          local cur = redis.call('HGET', account, 'active')
          if not cur or cur == '' then cur = '-' end
          if cur ~= expectActive then return {'ACTIVE', cur} end
        end
        redis.call('HSET', hash, 'rev', rev, 'rec', rec, 'pending', pending)
        finish()
        return {'OK'}
        """;

    /**
     * Keys: head hash, staged snapshot, stream snapshot hash, stream snapshot index, stream pin hash,
     * member account hash. Args: new head revision (R), head record, snapshot id, snapshots kept,
     * pending, expected active ('' no check), reservations. Prunes at most 256 older unpinned snapshots per call.
     */
    static final String PUBLISH_MEMBER = HEADER + """
        local refused = admit()
        if refused then return refused end
        local head = nk()
        local stage = nk()
        local snaps = nk()
        local idx = nk()
        local pins = nk()
        local account = nk()
        local rev = na()
        local rec = na()
        local snapshotId = na()
        local retain = na()
        local pending = na()
        local expectActive = na()
        local allowed = na()
        if not counter(rev) then return {'REFUSED', 'revision ' .. rev .. ' is outside 0..2^53-1'} end
        refused = keep(head, pending, allowed)
        if refused then return refused end
        local blob = redis.call('GET', stage)
        if not blob then return {'MISSING', 'the staged snapshot ' .. snapshotId} end
        if expectActive ~= '' then
          local cur = redis.call('HGET', account, 'active')
          if not cur or cur == '' then cur = '-' end
          if cur ~= expectActive then return {'ACTIVE', cur} end
        end
        redis.call('HSET', snaps, snapshotId, blob)
        redis.call('DEL', stage)
        redis.call('ZADD', idx, rev, snapshotId)
        redis.call('HSET', head, 'rev', rev, 'rec', rec, 'snap', snapshotId, 'pending', pending)
        local keep = math.max(1, tonumber(retain))
        local count = redis.call('ZCARD', idx)
        if count > keep then
          local ids = redis.call('ZRANGE', idx, 0, math.min(count - keep - 1, 255))
          for _, id in ipairs(ids) do
            if id ~= snapshotId and not redis.call('HGET', pins, id) then
              redis.call('HDEL', snaps, id)
              redis.call('ZREM', idx, id)
            end
          end
        end
        finish()
        return {'OK'}
        """;

    /**
     * PREPARE. Keys: intent hash, blob hash of the operation, staged after-image keys, reserved record
     * keys, compared record keys, pin hashes, index sets. Args: intent record, intent revision, phase tag,
     * kind tag, created ms, receipt resource id, blob count + lengths, reserved count + (revision, record)
     * pairs, compared count (records the intent compares without reserving; refused RESERVED when another
     * operation's reservation is on them), pin count + snapshot ids, index count + (member, cap) pairs.
     * Writes nothing on refusal.
     */
    static final String PREPARE_INTENT = HEADER + """
        local refused = gate() or replay()
        if refused then return refused end
        local intent = nk()
        local blobs = nk()
        local existing = redis.call('HMGET', intent, 'phase', 'digest', 'rec')
        if existing[1] then
          if existing[2] ~= ARGV[4] then return {'DIGEST'} end
          return {'INTENT', existing[1], existing[3] or ''}
        end
        refused = claims() or vector()
        if refused then return refused end
        local rec = na()
        local rev = na()
        local phase = na()
        local kind = na()
        local created = na()
        local resource = na()
        local nb = tonumber(na())
        local stages = {}
        local lens = {}
        for i = 1, nb do stages[i] = nk() end
        for i = 1, nb do lens[i] = tonumber(na()) end
        local data = {}
        for i = 1, nb do
          local b = redis.call('GET', stages[i])
          if not b then return {'MISSING', 'the staged after-image ' .. stages[i]} end
          if #b ~= lens[i] then return {'REFUSED', 'staged after-image ' .. stages[i] .. ' has ' .. #b .. ' bytes, expected ' .. lens[i]} end
          if string.sub(b, 1, 4) ~= 'AWFR' or string.byte(b, 5) ~= 2 then
            return {'REFUSED', 'staged after-image ' .. stages[i] .. ' is not a framed format-2 record'}
          end
          data[i] = b
        end
        local nr = tonumber(na())
        local rkeys = {}
        local rrev = {}
        local rrec = {}
        for i = 1, nr do rkeys[i] = nk() end
        for i = 1, nr do
          rrev[i] = na()
          rrec[i] = na()
          if not counter(rrev[i]) then return {'REFUSED', 'revision ' .. rrev[i] .. ' is outside 0..2^53-1'} end
        end
        for i = 1, nr do
          local r = reserved(rkeys[i])
          if r then return r end
        end
        local nc = tonumber(na())
        for i = 1, nc do
          local r = reserved(nk())
          if r then return r end
        end
        local np = tonumber(na())
        local pkeys = {}
        local pids = {}
        for i = 1, np do pkeys[i] = nk() end
        for i = 1, np do pids[i] = na() end
        local ni = tonumber(na())
        local ikeys = {}
        local imem = {}
        local icap = {}
        for i = 1, ni do ikeys[i] = nk() end
        for i = 1, ni do
          imem[i] = na()
          icap[i] = tonumber(na())
        end
        for i = 1, ni do
          if redis.call('ZCARD', ikeys[i]) >= icap[i] and not redis.call('ZSCORE', ikeys[i], imem[i]) then
            return {'LIMIT', 'pending operations indexed under ' .. ikeys[i]}
          end
        end
        redis.call('HSET', intent, 'rev', rev, 'rec', rec, 'phase', phase, 'digest', ARGV[4], 'kind', kind, 'created', created,
          'resource', resource)
        for i = 1, nb do
          redis.call('HSET', blobs, tostring(i - 1), data[i])
          redis.call('DEL', stages[i])
        end
        for i = 1, nr do redis.call('HSET', rkeys[i], 'rev', rrev[i], 'rec', rrec[i], 'pending', ARGV[3]) end
        for i = 1, np do redis.call('HINCRBY', pkeys[i], pids[i], 1) end
        for i = 1, ni do redis.call('ZADD', ikeys[i], 0, imem[i]) end
        redis.call('HINCRBY', KEYS[1], 'commits', 1)
        return {'OK'}
        """;

    /**
     * COMMIT (and roll-forward of a COMMITTED intent). Keys: intent hash, blob hash, then per
     * after-image: target hash and names hash (meta when not a profile), release record keys, pin
     * hashes, index sets to remove from, index sets to add to. Args: '1' for roll-forward (claims and
     * vector not re-checked), intent record (FINALIZED), its revision, the intent's deadline in epoch
     * milliseconds ('0' none; a PREPARED intent past it by Redis TIME is refused EXPIRED, a roll-forward
     * never is), install count + per install (blob index, revision, type, f1..f4), release count +
     * (revision, record) pairs, compared count (keys of compare-only records, refused RESERVED when
     * another operation holds them), pin count + ids, removal count + members, addition count +
     * (member, cap) pairs. In Redis COMMITTED and FINALIZED are one atomic step. The addition caps are the §9 bounds of
     * the membership indexes (64 open invites per profile and per invitee, one open vote per profile, 64
     * outstanding salvage per profile) and refuse LIMIT before any write.
     */
    static final String COMMIT_INTENT = HEADER + """
        local forward = na()
        local refused
        if forward == '1' then refused = gate() or replay() or room() else refused = admit() end
        if refused then return refused end
        local intent = nk()
        local blobs = nk()
        local cur = redis.call('HMGET', intent, 'phase', 'digest', 'rec')
        if not cur[1] then return {'NOINTENT'} end
        if cur[2] ~= ARGV[4] then return {'DIGEST'} end
        if cur[1] == '3' or cur[1] == '4' then return {'INTENT', cur[1], cur[3] or ''} end
        if forward == '1' and cur[1] ~= '2' then return {'INTENT', cur[1], cur[3] or ''} end
        local rec = na()
        local rev = na()
        local deadline = tonumber(na())
        if forward ~= '1' and deadline ~= nil and deadline > 0 then
          local t = now()
          if t > deadline then return {'EXPIRED', tostring(deadline), tostring(t)} end
        end
        local ni = tonumber(na())
        local ikeys = {}
        local inames = {}
        local iblob = {}
        local irev = {}
        local itype = {}
        local f1 = {}
        local f2 = {}
        local f3 = {}
        local f4 = {}
        for i = 1, ni do
          ikeys[i] = nk()
          inames[i] = nk()
        end
        for i = 1, ni do
          iblob[i] = na()
          irev[i] = na()
          itype[i] = na()
          f1[i] = na()
          f2[i] = na()
          f3[i] = na()
          f4[i] = na()
          if not counter(irev[i]) then return {'REFUSED', 'revision ' .. irev[i] .. ' is outside 0..2^53-1'} end
        end
        local data = {}
        for i = 1, ni do
          local b = redis.call('HGET', blobs, iblob[i])
          if not b then return {'MISSING', 'after-image ' .. iblob[i] .. ' of operation ' .. ARGV[3]} end
          data[i] = b
        end
        for i = 1, ni do
          local r = reserved(ikeys[i])
          if r then return r end
          if itype[i] == '2' then
            local holder = redis.call('HGET', inames[i], f1[i])
            if holder and holder ~= f4[i] then return {'NAME', holder} end
          end
        end
        local nr = tonumber(na())
        local rkeys = {}
        local rrev = {}
        local rrec = {}
        for i = 1, nr do rkeys[i] = nk() end
        for i = 1, nr do
          rrev[i] = na()
          rrec[i] = na()
          if not counter(rrev[i]) then return {'REFUSED', 'revision ' .. rrev[i] .. ' is outside 0..2^53-1'} end
        end
        for i = 1, nr do
          local r = reserved(rkeys[i])
          if r then return r end
        end
        local nc = tonumber(na())
        for i = 1, nc do
          local r = reserved(nk())
          if r then return r end
        end
        local np = tonumber(na())
        local pkeys = {}
        local pids = {}
        for i = 1, np do pkeys[i] = nk() end
        for i = 1, np do pids[i] = na() end
        local nx = tonumber(na())
        local xkeys = {}
        local xmem = {}
        for i = 1, nx do xkeys[i] = nk() end
        for i = 1, nx do xmem[i] = na() end
        local ny = tonumber(na())
        local ykeys = {}
        local ymem = {}
        local ycap = {}
        for i = 1, ny do ykeys[i] = nk() end
        for i = 1, ny do
          ymem[i] = na()
          ycap[i] = tonumber(na())
        end
        for i = 1, ny do
          if redis.call('ZCARD', ykeys[i]) >= ycap[i] and not redis.call('ZSCORE', ykeys[i], ymem[i]) then
            return {'LIMIT', 'records indexed under ' .. ykeys[i]}
          end
        end
        for i = 1, ni do
          local key = ikeys[i]
          if itype[i] == '2' then
            local old = redis.call('HGET', key, 'name')
            if old and old ~= f1[i] and redis.call('HGET', inames[i], old) == f4[i] then redis.call('HDEL', inames[i], old) end
            if f3[i] == '1' then
              if redis.call('HGET', inames[i], f1[i]) == f4[i] then redis.call('HDEL', inames[i], f1[i]) end
            else
              redis.call('HSET', inames[i], f1[i], f4[i])
            end
            redis.call('HSET', key, 'name', f1[i], 'account', f2[i])
          elseif itype[i] == '1' then
            redis.call('HSET', key, 'active', f1[i])
          elseif itype[i] == '11' then
            redis.call('HSET', key, 'snap', f1[i])
          end
          redis.call('HSET', key, 'rev', irev[i], 'rec', data[i], 'pending', '')
        end
        for i = 1, nr do redis.call('HSET', rkeys[i], 'rev', rrev[i], 'rec', rrec[i], 'pending', '') end
        for i = 1, np do
          if redis.call('HINCRBY', pkeys[i], pids[i], -1) <= 0 then redis.call('HDEL', pkeys[i], pids[i]) end
        end
        for i = 1, nx do redis.call('ZREM', xkeys[i], xmem[i]) end
        for i = 1, ny do redis.call('ZADD', ykeys[i], 0, ymem[i]) end
        redis.call('HSET', intent, 'rev', rev, 'rec', rec, 'phase', '3')
        redis.call('DEL', blobs)
        finish()
        return {'OK'}
        """;

    /**
     * ABORT. Keys: intent hash, blob hash, release record keys, pin hashes, index sets to remove from.
     * Args: intent record (ABORTED), its revision, release count + (revision, record) pairs, pin
     * count + ids, removal count + members. Refused once the intent is COMMITTED or FINALIZED.
     */
    static final String ABORT_INTENT = HEADER + """
        local refused = admit()
        if refused then return refused end
        local intent = nk()
        local blobs = nk()
        local cur = redis.call('HMGET', intent, 'phase', 'digest', 'rec')
        if not cur[1] then return {'NOINTENT'} end
        if cur[2] ~= ARGV[4] then return {'DIGEST'} end
        if cur[1] == '2' or cur[1] == '3' then return {'DECIDED', cur[1]} end
        if cur[1] == '4' then return {'INTENT', cur[1], cur[3] or ''} end
        local rec = na()
        local rev = na()
        local nr = tonumber(na())
        local rkeys = {}
        local rrev = {}
        local rrec = {}
        for i = 1, nr do rkeys[i] = nk() end
        for i = 1, nr do
          rrev[i] = na()
          rrec[i] = na()
          if not counter(rrev[i]) then return {'REFUSED', 'revision ' .. rrev[i] .. ' is outside 0..2^53-1'} end
        end
        for i = 1, nr do
          local r = reserved(rkeys[i])
          if r then return r end
        end
        local np = tonumber(na())
        local pkeys = {}
        local pids = {}
        for i = 1, np do pkeys[i] = nk() end
        for i = 1, np do pids[i] = na() end
        local nx = tonumber(na())
        local xkeys = {}
        local xmem = {}
        for i = 1, nx do xkeys[i] = nk() end
        for i = 1, nx do xmem[i] = na() end
        for i = 1, nr do redis.call('HSET', rkeys[i], 'rev', rrev[i], 'rec', rrec[i], 'pending', '') end
        for i = 1, np do
          if redis.call('HINCRBY', pkeys[i], pids[i], -1) <= 0 then redis.call('HDEL', pkeys[i], pids[i]) end
        end
        for i = 1, nx do redis.call('ZREM', xkeys[i], xmem[i]) end
        redis.call('HSET', intent, 'rev', rev, 'rec', rec, 'phase', '4')
        redis.call('DEL', blobs)
        finish()
        return {'OK'}
        """;

    /**
     * Prefix of every {@link ProfileMigrationStore} write script: accepted only while the {@code meta}
     * hash (KEYS[1]) says format 2 with the migration IN_PROGRESS (tag 2). Redis has no migration lock;
     * the marker is the only gate, so each of these writes re-checks it atomically.
     */
    static final String MIGRATION_HEADER = """
        #!lua
        local m = redis.call('HMGET', KEYS[1], 'format', 'migrationState')
        if (m[1] or '1') ~= '2' or (m[2] or '1') ~= '2' then
          return {'FORMAT', 'migration writes are accepted only while the store marker says format 2 with the migration in progress'}
        end
        """;

    /** Rewrites one pre-profile archive field. KEYS[2] legacy hash. ARGV[1] field (snapshot id), [2] format-2 frame. */
    static final String MIGRATE_ARCHIVE = MIGRATION_HEADER + """
        if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 0 then return {'REFUSED', 'archive ' .. ARGV[1] .. ' is gone'} end
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
        return {'OK'}
        """;

    /**
     * Installs one migrated member snapshot (immutable: the same bytes again are a no-op, other bytes are
     * refused). KEYS[2] stream snapshot hash, [3] stream snapshot index. ARGV[1] snapshot id, [2] frame, [3] revision.
     */
    static final String MIGRATE_MEMBER_SNAPSHOT = MIGRATION_HEADER + """
        local cur = redis.call('HGET', KEYS[2], ARGV[1])
        if cur then
          if cur ~= ARGV[2] then return {'REFUSED', 'member snapshot ' .. ARGV[1] .. ' already exists with other content'} end
          return {'OK'}
        end
        redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
        redis.call('ZADD', KEYS[3], ARGV[3], ARGV[1])
        return {'OK'}
        """;

    /** Installs one migrated stream head; refused when a different head is stored. KEYS[2] head hash. ARGV[1] revision, [2] frame, [3] snapshot id or ''. */
    static final String MIGRATE_HEAD = MIGRATION_HEADER + """
        local cur = redis.call('HGET', KEYS[2], 'rec')
        if cur then
          if cur ~= ARGV[2] then return {'REFUSED', 'the member stream already has another head'} end
          return {'OK'}
        end
        redis.call('HSET', KEYS[2], 'rev', ARGV[1], 'rec', ARGV[2], 'snap', ARGV[3], 'pending', '')
        return {'OK'}
        """;

    /**
     * Drops a converted profile's format-1 snapshots. KEYS[2] profile hash, [3] format-1 snapshot hash,
     * [4] its index. Refused while the profile record is still a format-1 frame (byte 5 of the frame is the
     * format). Replies {@code OK <dropped>}.
     */
    static final String MIGRATE_DROP_SNAPSHOTS = MIGRATION_HEADER + """
        local rec = redis.call('HGET', KEYS[2], 'rec')
        if not rec or string.byte(rec, 5) ~= 2 then return {'REFUSED', 'the profile record is not format 2 yet; its snapshots are kept'} end
        local n = redis.call('ZCARD', KEYS[4])
        redis.call('DEL', KEYS[3], KEYS[4])
        redis.call('HDEL', KEYS[2], 'latest')
        return {'OK', tostring(n)}
        """;

    /**
     * Retires one terminal format-1 journal (the Java side checked both). KEYS[2] journals of the player,
     * [3] their metadata, [4] journal-to-player index, [5] pending set, [6] failed index. ARGV[1] transition
     * id, [2] player.
     */
    static final String MIGRATE_RETIRE_JOURNAL = MIGRATION_HEADER + """
        if redis.call('HGET', KEYS[4], ARGV[1]) ~= ARGV[2] then return {'OK'} end
        redis.call('HDEL', KEYS[2], ARGV[1])
        redis.call('HDEL', KEYS[3], ARGV[1])
        redis.call('HDEL', KEYS[4], ARGV[1])
        redis.call('SREM', KEYS[5], ARGV[1])
        redis.call('ZREM', KEYS[6], ARGV[1])
        return {'OK'}
        """;

    /**
     * Store-format marker. KEYS[1] meta. ARGV: format, migration tag, schema, written ms, written by,
     * the highest format this build knows. Refused when the stored format is one this build does not know,
     * and when the marker to write is not that build's format (a downgrade marker is never written).
     */
    static final String WRITE_FORMAT = """
        #!lua
        local stored = redis.call('HGET', KEYS[1], 'format')
        if stored and (tonumber(stored) or 0) > tonumber(ARGV[6]) then
          return {'FORMAT', 'the store is format ' .. stored .. ', which this build does not know; nothing was written'}
        end
        if tonumber(ARGV[1]) ~= tonumber(ARGV[6]) then
          return {'FORMAT', 'this build writes only format ' .. ARGV[6] .. ' markers; a format ' .. ARGV[1] .. ' (downgrade) marker is never written'}
        end
        local level = redis.call('HMGET', KEYS[1], 'schema', 'minimumBuild')
        if stored and (tonumber(stored) or 0) == tonumber(ARGV[6])
          and ((tonumber(level[1] or '0') or 0) > tonumber(ARGV[3]) or (tonumber(level[2] or '0') or 0) > 0) then
          return {'FORMAT', 'the store was raised to schema ' .. tostring(level[1]) .. ' / Build ' .. tostring(level[2] or '0')
            .. '; a marker below that is never written'}
        end
        redis.call('HSET', KEYS[1], 'format', ARGV[1], 'migrationState', ARGV[2], 'schema', ARGV[3], 'writtenAt', ARGV[4],
          'writtenBy', ARGV[5])
        return {'OK'}
        """;

    /**
     * Store feature level (Build 52). KEYS[1] meta. ARGV: schema, minimum build, written ms, written by, the format
     * this build writes. Refused unless the store is that format and not migrating; raises {@code schema} and
     * {@code minimumBuild} to at least the arguments and never lowers them.
     */
    static final String RAISE_FORMAT = """
        #!lua
        local m = redis.call('HMGET', KEYS[1], 'format', 'migrationState', 'schema', 'minimumBuild')
        if m[1] ~= ARGV[5] then
          return {'FORMAT', 'the store is format ' .. tostring(m[1] or '1') .. ', not ' .. ARGV[5]}
        end
        if m[2] == '2' then return {'FORMAT', 'a format-2 migration is in progress'} end
        local schema = math.max(tonumber(m[3] or '0') or 0, tonumber(ARGV[1]))
        local minimum = math.max(tonumber(m[4] or '0') or 0, tonumber(ARGV[2]))
        redis.call('HSET', KEYS[1], 'schema', tostring(schema), 'minimumBuild', tostring(minimum), 'writtenAt', ARGV[3],
          'writtenBy', ARGV[4])
        return {'OK'}
        """;
}
