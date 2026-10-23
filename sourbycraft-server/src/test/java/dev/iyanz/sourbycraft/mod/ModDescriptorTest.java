package dev.iyanz.sourbycraft.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModDescriptorTest {

    private static ModDescriptor parse(final String yaml) {
        return ModDescriptor.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.jar");
    }

    @Test
    void parsesTheMinimalDescriptor() {
        final ModDescriptor descriptor = parse("""
            id: example-mod
            name: Example Mod
            version: 1.0.0
            main: com.example.ExampleMod
            api: 1
            """);
        assertNotNull(descriptor);
        assertEquals("example-mod", descriptor.id);
        assertEquals("Example Mod", descriptor.name);
        assertEquals("1.0.0", descriptor.version);
        assertEquals("com.example.ExampleMod", descriptor.main);
        assertEquals(1, descriptor.api);
    }

    @Test
    void nameDefaultsToTheId() {
        final ModDescriptor descriptor = parse("""
            id: bare
            version: 0.1
            main: a.B
            api: 1
            """);
        assertNotNull(descriptor);
        assertEquals("bare", descriptor.name);
    }

    @Test
    void rejectsMissingOrInvalidFields() {
        assertNull(parse("version: 1\nmain: a.B\napi: 1\n"), "missing id");
        assertNull(parse("id: Bad Id\nversion: 1\nmain: a.B\napi: 1\n"), "invalid id");
        assertNull(parse("id: ok\nmain: a.B\napi: 1\n"), "missing version");
        assertNull(parse("id: ok\nversion: 1\napi: 1\n"), "missing main");
        assertNull(parse("id: ok\nversion: 1\nmain: a.B\n"), "missing api");
        assertNull(parse("id: ok\nversion: 1\nmain: a.B\napi: 0\n"), "api below 1");
        assertNull(parse("api: nope\n"), "non-numeric api");
    }

    @Test
    void refusesYamlGadgetTags() {
        // SafeConstructor must reject a tag that would instantiate an arbitrary class.
        assertNull(parse("id: ok\nversion: 1\nmain: !!java.lang.ProcessBuilder a.B\napi: 1\n"));
    }
}
