package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Node directory and ownership lookup.
 *
 * <p>Answers the questions a proxy or a game plugin asks without depending on one: which node owns a
 * profile, which node owns a world, which nodes are alive, and which node has room. The answers come
 * from the session and coordination claims, so there is no second, divergent source of truth.</p>
 *
 * <p>Routing is deliberately abstract: this class never opens a connection to another node and never
 * requires Leviathan or AbyssLink. A proxy integration implements its own transport and only asks
 * this directory where to send a player.</p>
 */
public final class RoutingFabric {

    /**
     * Writes the node hash, its TTL and the index entry in one script: a caller killed between HSET
     * and PEXPIRE would otherwise leave a node record that never expires and is reported alive for
     * ever.
     */
    private static final String REGISTER = """
        redis.call('HSET', KEYS[1], 'address', ARGV[1], 'capacity', ARGV[2], 'players', ARGV[3],
          'state', ARGV[4], 'heartbeat', ARGV[5])
        redis.call('PEXPIRE', KEYS[1], ARGV[6])
        redis.call('SADD', KEYS[2], ARGV[7])
        return 1
        """;

    /** A node's advertised presence. */
    public record NodeInfo(String id, String address, int capacity, int players, String state, long heartbeatMillis) {
        public NodeInfo {
            if (id == null || id.isEmpty() || id.length() > 64) throw new IllegalArgumentException("node id");
            if (address == null) address = "";
            if (capacity < 1) capacity = 1;
            if (players < 0) players = 0;
            if (state == null || state.isEmpty()) state = "UNKNOWN";
        }

        public boolean healthy() {
            return "HEALTHY".equals(this.state);
        }

        public double load() {
            return (double) this.players / (double) this.capacity;
        }
    }

    private final RedisClient client;
    private final String prefix;
    private final FabricMetrics metrics;
    private final SessionFabric sessions;
    private final CoordinationFabric coordination;
    private final int nodeTtlSeconds;

    RoutingFabric(final RedisClient client, final String prefix, final FabricMetrics metrics,
                  final SessionFabric sessions, final CoordinationFabric coordination, final int nodeTtlSeconds) {
        this.client = client;
        this.prefix = prefix;
        this.metrics = metrics;
        this.sessions = sessions;
        this.coordination = coordination;
        this.nodeTtlSeconds = nodeTtlSeconds;
    }

    /** Registers or replaces this node's record; the key expires if heartbeats stop. */
    public void register(final NodeInfo node) throws IOException {
        this.client.eval(REGISTER, 2, nodeKey(node.id()), nodeIndex(),
            node.address(), Integer.toString(node.capacity()), Integer.toString(node.players()), node.state(),
            Long.toString(node.heartbeatMillis()), Long.toString(this.nodeTtlSeconds * 1000L), node.id());
    }

    public void heartbeat(final NodeInfo node) throws IOException {
        register(node);
    }

    /** Removes the node from the index; its key also expires on its own. */
    public void unregister(final String nodeId) throws IOException {
        this.client.call("SREM", nodeIndex(), nodeId);
        this.client.call("DEL", nodeKey(nodeId));
    }

    /** Whether the node has a live record. */
    public boolean alive(final String nodeId) throws IOException {
        return RedisReplies.number(this.client.call("EXISTS", nodeKey(nodeId)), 0L) == 1L;
    }

    public Optional<NodeInfo> node(final String nodeId) throws IOException {
        final Map<String, String> fields = RedisReplies.map(this.client.call("HGETALL", nodeKey(nodeId)));
        if (fields.isEmpty()) return Optional.empty();
        return Optional.of(decode(nodeId, fields));
    }

    /** Every live node, sorted by id. Dead index entries are pruned opportunistically. */
    public List<NodeInfo> nodes() throws IOException {
        final List<String> ids = RedisReplies.strings(this.client.call("SMEMBERS", nodeIndex()));
        if (ids.isEmpty()) return List.of();
        final List<Object[]> commands = new ArrayList<>(ids.size());
        for (final String id : ids) commands.add(new Object[] {"HGETALL", nodeKey(id)});
        final List<Object> replies = this.client.pipeline(commands);
        final List<NodeInfo> nodes = new ArrayList<>(ids.size());
        final List<String> dead = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            final Map<String, String> fields = RedisReplies.map(replies.get(i));
            if (fields.isEmpty()) {
                dead.add(ids.get(i));
            } else {
                nodes.add(decode(ids.get(i), fields));
            }
        }
        if (!dead.isEmpty()) {
            for (final String id : dead) this.client.call("SREM", nodeIndex(), id);
        }
        nodes.sort(Comparator.comparing(NodeInfo::id));
        return List.copyOf(nodes);
    }

    /** The least-loaded healthy node with room for {@code requiredCapacity}, if any. */
    public Optional<NodeInfo> availableNode(final int requiredCapacity) throws IOException {
        return nodes().stream()
            .filter(NodeInfo::healthy)
            .filter(node -> node.capacity() - node.players() >= Math.max(1, requiredCapacity))
            .min(Comparator.comparingDouble(NodeInfo::load).thenComparing(NodeInfo::id));
    }

    /** The node that holds a profile session. */
    public Optional<OwnerClaim> profileOwner(final UUID profileId) throws IOException {
        return Optional.ofNullable(this.sessions.holder(profileId));
    }

    /** The node that holds a world's write claim. */
    public Optional<OwnerClaim> worldOwner(final String world) throws IOException {
        return Optional.ofNullable(this.coordination.current("world", world));
    }

    /** The node that holds any coordination object. */
    public Optional<OwnerClaim> owner(final String kind, final String id) throws IOException {
        return Optional.ofNullable(this.coordination.current(kind, id));
    }

    public FabricMetrics metrics() {
        return this.metrics;
    }

    private NodeInfo decode(final String id, final Map<String, String> fields) {
        final long heartbeat = parseLong(fields.get("heartbeat"));
        return new NodeInfo(id, fields.getOrDefault("address", ""), (int) Math.max(1L, parseLong(fields.get("capacity"))),
            (int) Math.max(0L, parseLong(fields.get("players"))), fields.getOrDefault("state", "UNKNOWN"), heartbeat);
    }

    private static long parseLong(final String text) {
        if (text == null) return 0L;
        try {
            return Long.parseLong(text);
        } catch (final NumberFormatException malformed) {
            return 0L;
        }
    }

    private String nodeKey(final String nodeId) {
        return this.prefix + "node:" + nodeId;
    }

    private String nodeIndex() {
        return this.prefix + "nodes";
    }

    /** Snapshot for diagnostics: node rows with their liveness. */
    public Map<String, NodeInfo> directory() throws IOException {
        final Map<String, NodeInfo> nodes = new LinkedHashMap<>();
        for (final NodeInfo node : nodes()) nodes.put(node.id(), node);
        return nodes;
    }
}
