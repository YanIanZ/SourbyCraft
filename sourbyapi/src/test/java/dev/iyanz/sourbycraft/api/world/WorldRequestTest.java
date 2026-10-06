package dev.iyanz.sourbycraft.api.world;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.junit.jupiter.api.Test;

class WorldRequestTest {
    @Test
    void persistentRequestsAreImmutableAndKeepNamedTerrainSettings() {
        final var defaults = WorldRequest.persistent("survival");
        final var configured = defaults.withSeed(123L).withEnvironment(World.Environment.NETHER)
            .withType(WorldType.FLAT).withGenerator("Terrain:nether").withAutoload(true);
        assertNull(defaults.seed());
        assertNull(defaults.generator());
        assertFalse(defaults.autoload());
        assertEquals(World.Environment.NORMAL, defaults.environment());
        assertEquals(123L, configured.seed());
        assertEquals(World.Environment.NETHER, configured.environment());
        assertEquals(WorldType.FLAT, configured.type());
        assertEquals("Terrain:nether", configured.generator());
        assertTrue(configured.autoload());
    }

    @Test
    void templateRequestsInheritTerrainAndOnlyChangeAutoload() {
        final var defaults = WorldRequest.fromTemplate("island", "arena_1");
        final var configured = defaults.withAutoload(true);
        assertFalse(defaults.autoload());
        assertEquals("island", configured.template());
        assertEquals("arena_1", configured.name());
        assertTrue(configured.autoload());
        assertEquals(3, WorldRequest.TemplateClone.class.getRecordComponents().length);
    }

    @Test
    void newCreationEntryPointDelegatesToExistingApiMethods() {
        final AtomicReference<String> called = new AtomicReference<>();
        final AtomicReference<Object[]> arguments = new AtomicReference<>();
        final CompletableFuture<World> pending = new CompletableFuture<>();
        final AuroraWorlds worlds = (AuroraWorlds) Proxy.newProxyInstance(AuroraWorlds.class.getClassLoader(),
            new Class<?>[]{AuroraWorlds.class}, (proxy, method, args) -> {
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                called.set(method.getName());
                arguments.set(args);
                return pending;
            });
        assertSame(pending, worlds.create(WorldRequest.fromTemplate("island", "arena_1").withAutoload(true)));
        assertEquals("createFromTemplate", called.get());
        assertArrayEquals(new Object[]{"island", "arena_1", true}, arguments.get());
        assertSame(pending, worlds.create(WorldRequest.persistent("survival").withSeed(7L).withGenerator("void")));
        assertEquals("create", called.get());
        final WorldCreator creator = (WorldCreator) arguments.get()[0];
        assertEquals("survival", creator.name());
        assertEquals(7L, creator.seed());
        assertEquals("void", arguments.get()[1]);
        assertEquals(false, arguments.get()[2]);
    }
}
