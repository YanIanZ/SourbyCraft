package dev.iyanz.aurora.engine.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.aurora.engine.util.Util;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.helpers.MessageFormatter;

/**
 * The region watchdog's "has not responded in" lines (FoliaWatchdogThread, engine baseline; fixed by
 * feature patch "region watchdog prints the elapsed time with one unit") take the elapsed time from
 * {@link Util#formatNanosToLargestDecimalUnit}, which already ends in its unit. The baseline put a
 * literal {@code s} after the placeholder too, so the log read "5.93ss" (server-w2 wdon-dumps.txt).
 */
class WatchdogMessageTest {

    private static List<String> watchdogMessages() throws IOException {
        final ClassNode node = new ClassNode();
        try (InputStream in = WatchdogMessageTest.class.getClassLoader()
                .getResourceAsStream("io/papermc/paper/threadedregions/FoliaWatchdogThread.class")) {
            assertTrue(in != null, "FoliaWatchdogThread on the test classpath");
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        final List<String> messages = new ArrayList<>();
        for (final MethodNode method : node.methods) {
            for (final var insn : method.instructions.toArray()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && s.contains("has not responded in")) {
                    messages.add(s);
                }
            }
        }
        return messages;
    }

    @Test
    void theWatchdogLinesCarryTheFormattersUnitOnly() throws IOException {
        assertEquals(List.of(
            "Tick region located in world '{}' around chunk '{}' has not responded in {}:",
            "Global region has not responded in {}:"), watchdogMessages());
    }

    @Test
    void theFormattedLineReadsOneUnit() throws IOException {
        final String elapsed = Util.formatNanosToLargestDecimalUnit(5_930_000_000L);
        assertEquals("5.93s", elapsed);
        final String global = watchdogMessages().get(1);
        assertEquals("Global region has not responded in 5.93s:",
            MessageFormatter.arrayFormat(global, new Object[] {elapsed}).getMessage());
        final String region = watchdogMessages().get(0);
        assertEquals("Tick region located in world 'world' around chunk '[0, 0]' has not responded in 5.93s:",
            MessageFormatter.arrayFormat(region, new Object[] {"world", "[0, 0]", elapsed}).getMessage());
    }
}
