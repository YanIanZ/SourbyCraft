package dev.iyanz.sourbycraft.swm.loader;

import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import dev.iyanz.sourbycraft.swm.loader.util.StringByteCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class RedisLoader implements SlimeLoader {

    private static final String WORLD_DATA_PREFIX = "aswm:world:data:";
    private static final String WORLD_LIST_PREFIX = "aswm:world:list";

    private final RedisCommands<String, byte[]> connection;
    private final io.lettuce.core.api.async.RedisAsyncCommands<String, byte[]> asyncConn;

    public RedisLoader(String uri) {
        var conn = RedisClient.create(uri).connect(StringByteCodec.INSTANCE);
        this.connection = conn.sync();
        this.asyncConn = conn.async();
    }

    @Override
    public byte[] readWorld(String name) throws IOException {
        byte[] data = connection.get(WORLD_DATA_PREFIX + name);
        if (data == null) {
            throw new IOException(name);
        }
        return data;
    }

    @Override
    public boolean worldExists(String name) throws IOException {
        return connection.exists(WORLD_DATA_PREFIX + name) == 1;
    }

    @Override
    public List<String> listWorlds() throws IOException {
        return connection.smembers(WORLD_LIST_PREFIX)
                .stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .collect(Collectors.toList()); // We can't use .toList because this needs to be mutable.
    }

    @Override
    public void saveWorld(String worldName, byte[] bytes) throws IOException {
        asyncConn.setAutoFlushCommands(false);
        try {
            var setFuture = asyncConn.set(WORLD_DATA_PREFIX + worldName, bytes);
            var saddFuture = asyncConn.sadd(WORLD_LIST_PREFIX,
                    worldName.getBytes(StandardCharsets.UTF_8));
            asyncConn.flushCommands();
            io.lettuce.core.LettuceFutures.awaitAll(
                    java.time.Duration.ofSeconds(10), setFuture, saddFuture);
        } catch (Exception e) {
            throw new IOException("Redis saveWorld failed for " + worldName, e);
        } finally {
            asyncConn.setAutoFlushCommands(true);
        }
    }

    @Override
    public void deleteWorld(String worldName) throws IOException {
        long deletedCount = connection.del(WORLD_DATA_PREFIX + worldName);

        // We're checking equal to zero, because the lock key doesn't have to exist
        if (deletedCount == 0) {
            throw new IOException(worldName);
        }

        // Remove the world from the world list set
        connection.srem(WORLD_LIST_PREFIX, worldName.getBytes(StandardCharsets.UTF_8));
    }
}