package dev.iyanz.sourbycraft.perf;

import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionMetricsLifecycleIntegrationTest {

    @Test
    void canonicalRegistryCannotBeConstructedOutsideItsPackage() throws Exception {
        assertFalse(Modifier.isPublic(RegionMetricsRegistry.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPublic(RegionMetricsRegistry.class.getField("INSTANCE").getModifiers()));
        assertTrue(Modifier.isStatic(RegionMetricsRegistry.class.getField("INSTANCE").getModifiers()));
        assertTrue(Modifier.isFinal(RegionMetricsRegistry.class.getField("INSTANCE").getModifiers()));
    }

    @Test
    void schedulerHandleAndTickDataViewsUseMutableOwnerHolder() throws Exception {
        assertEquals(RegionTickMetricsHolder.class,
            TickRegionScheduler.RegionScheduleHandle.class.getField("sourbyTickMetrics").getType());
        assertEquals(RegionTickMetricsHolder.class,
            ca.spottedleaf.common.time.TickData.class
                .getConstructor(RegionTickMetricsHolder.class, long.class).getParameterTypes()[0]);
    }

    @Test
    void mergeCallbacksNeverMutateSchedulerOrCopyHandles() throws Exception {
        assertMethodDoesNotInvoke(TickRegions.class, "preMerge", Set.of(
            "scheduleRegion", "descheduleRegion", "copy"
        ));
        assertMethodDoesNotInvoke(TickRegions.TickRegionData.class, "mergeInto", Set.of(
            "scheduleRegion", "descheduleRegion", "copy"
        ));
        assertMethodDoesNotInvoke(TickRegions.class, "rotateMergeTargetTelemetry", Set.of(
            "scheduleRegion", "descheduleRegion", "copy"
        ));
    }

    @Test
    void mergeRotationRetiresBeforeClearingAndReplacingOwner() throws Exception {
        final java.util.List<String> calls = invocations(
            RegionMetricsRegistry.class, "rotateForMerge", "RegionTickMetricsHolder"
        ).stream().map(invocation -> invocation.name().stringValue()).collect(Collectors.toList());

        assertTrue(calls.indexOf("retire") < calls.indexOf("clearGeneration"));
        assertTrue(calls.indexOf("clearGeneration") < calls.indexOf("replaceOwnerAfterRetirement"));
    }

    @Test
    void holderTickPathAllocatesNoObjectsOrArrays() throws Exception {
        final String resource = "/" + RegionTickMetricsHolder.class.getName().replace('.', '/') + ".class";
        try (InputStream input = RegionTickMetricsHolder.class.getResourceAsStream(resource)) {
            assertTrue(input != null);
            for (final MethodModel method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if (!Set.of("tickStarted", "tickCompleted").contains(method.methodName().stringValue())) {
                    continue;
                }
                for (final CodeElement element : method.code().orElseThrow()) {
                    if (element instanceof Instruction instruction) {
                        assertFalse(instruction.opcode() == java.lang.classfile.Opcode.NEW
                            || instruction.opcode() == java.lang.classfile.Opcode.NEWARRAY
                            || instruction.opcode() == java.lang.classfile.Opcode.ANEWARRAY
                            || instruction.opcode() == java.lang.classfile.Opcode.MULTIANEWARRAY);
                    }
                }
            }
        }
    }

    @Test
    void expiryUsesIdentityAwareMapRemoval() throws Exception {
        boolean found = false;
        for (final InvokeInstruction invocation : invocations(RegionMetricsRegistry.class, "forEachUnexpired")) {
            if (invocation.owner().asInternalName().equals("java/util/concurrent/ConcurrentHashMap")
                && invocation.name().stringValue().equals("remove")
                && invocation.type().stringValue().equals("(Ljava/lang/Object;Ljava/lang/Object;)Z")) {
                found = true;
            }
        }
        assertTrue(found, "expiry must condition removal on the observed generation identity");
    }

    private static void assertMethodDoesNotInvoke(final Class<?> type, final String methodName,
                                                  final Set<String> forbiddenNames) throws IOException {
        for (final InvokeInstruction invocation : invocations(type, methodName)) {
            assertFalse(forbiddenNames.contains(invocation.name().stringValue()),
                () -> type.getName() + "." + methodName + " invokes " + invocation.name().stringValue());
        }
    }

    private static java.util.List<InvokeInstruction> invocations(final Class<?> type,
                                                                 final String methodName) throws IOException {
        return invocations(type, methodName, null);
    }

    private static java.util.List<InvokeInstruction> invocations(final Class<?> type,
                                                                 final String methodName,
                                                                 final String descriptorFragment) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        final java.util.ArrayList<InvokeInstruction> result = new java.util.ArrayList<>();
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertTrue(input != null);
            for (final MethodModel method : ClassFile.of().parse(input.readAllBytes()).methods()) {
                if (!method.methodName().stringValue().equals(methodName)
                    || descriptorFragment != null && !method.methodType().stringValue().contains(descriptorFragment)) {
                    continue;
                }
                for (final CodeElement element : method.code().orElseThrow()) {
                    if (element instanceof InvokeInstruction invocation) {
                        result.add(invocation);
                    }
                }
            }
        }
        return result;
    }
}
