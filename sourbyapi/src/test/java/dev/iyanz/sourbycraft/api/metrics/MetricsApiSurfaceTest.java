package dev.iyanz.sourbycraft.api.metrics;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsApiSurfaceTest {
    @Test
    void apiHasNoServerImplementationTypes() throws NoSuchMethodException {
        assertEquals(PerformanceSnapshot.class, SourbyMetrics.class.getMethod("snapshot").getReturnType());
        for (Class<?> type : new Class<?>[]{SourbyMetrics.class, PerformanceSnapshot.class,
                WindowMetrics.class, RuntimeMetrics.class, Freshness.class}) {
            for (Method method : type.getMethods()) {
                assertPublicApiType(method.getGenericReturnType(), method.toGenericString());
                for (Type parameter : method.getGenericParameterTypes()) {
                    assertPublicApiType(parameter, method.toGenericString());
                }
            }
        }
    }

    @Test
    void methodDescriptorsArePinnedBeforeInitialRelease() {
        final Map<Class<?>, Map<String, String>> expected = Map.of(
            SourbyMetrics.class, Map.of("snapshot", "()PerformanceSnapshot"),
            PerformanceSnapshot.class, Map.of(
                "sequence", "()long", "sampledAtEpochMillis", "()long", "targetTps", "()double",
                "activeRegionCount", "()int", "retainedGenerationCount", "()int",
                "freshness", "()Freshness", "window", "(MetricWindow)WindowMetrics",
                "globalWindow", "(MetricWindow)WindowMetrics", "runtime", "()RuntimeMetrics"),
            WindowMetrics.class, Map.ofEntries(
                Map.entry("coverageMillis", "()long"), Map.entry("sampleCount", "()long"),
                Map.entry("approximate", "()boolean"), Map.entry("truncated", "()boolean"),
                Map.entry("worstTps", "()double"), Map.entry("medianTps", "()double"),
                Map.entry("aggregateTps", "()double"), Map.entry("worstAverageMspt", "()double"),
                Map.entry("aggregateAverageMspt", "()double"), Map.entry("medianAverageMspt", "()double"),
                Map.entry("minimumMspt", "()double"), Map.entry("maximumMspt", "()double"),
                Map.entry("medianMspt", "()double"), Map.entry("estimatedP95Mspt", "()double"),
                Map.entry("estimatedP99Mspt", "()double"), Map.entry("busiestUtilisation", "()double"),
                Map.entry("averageUtilisation", "()double"), Map.entry("totalMissingCpuMs", "()double")),
            RuntimeMetrics.class, Map.of(
                "heapUsedBytes", "()long", "heapMaxBytes", "()long", "rssPercent", "()double",
                "gcTimePercent", "()double", "gcCollectionsPerMinute", "()double",
                "averageGcPauseMs", "()double"),
            Freshness.class, Map.of("state", "()MetricState", "ageMillis", "()long",
                "collectorLatenessMillis", "()long", "scanDurationNanos", "()long",
                "diagnostic", "()String")
        );
        expected.forEach((type, methods) -> assertEquals(methods, descriptors(type), type.getName()));
    }

    @Test
    void windowsAreStableAndComplete() {
        assertArrayEquals(new MetricWindow[]{MetricWindow.FIVE_SECONDS, MetricWindow.TEN_SECONDS,
            MetricWindow.ONE_MINUTE, MetricWindow.FIVE_MINUTES, MetricWindow.FIFTEEN_MINUTES},
            MetricWindow.values());
    }

    private static Map<String, String> descriptors(final Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredMethods()).collect(java.util.stream.Collectors.toMap(
            Method::getName, method -> "(" + java.util.Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName).collect(java.util.stream.Collectors.joining(",")) + ")"
                + method.getReturnType().getSimpleName()));
    }

    private static void assertPublicApiType(final Type type, final String signature) {
        final String name = type.getTypeName();
        assertFalse(name.contains("net.minecraft"), signature);
        assertFalse(name.contains("craftbukkit"), signature);
        assertFalse(name.contains("io.canvasmc"), signature);
        assertFalse(name.contains("me.lucko.spark"), signature);
        assertFalse(name.contains("dev.iyanz.sourbycraft.perf"), signature);
        if (type instanceof Class<?> clazz && clazz.isArray()) {
            assertPublicApiType(clazz.getComponentType(), signature);
        } else if (type instanceof ParameterizedType parameterized) {
            assertPublicApiType(parameterized.getRawType(), signature);
            for (final Type argument : parameterized.getActualTypeArguments()) {
                assertPublicApiType(argument, signature);
            }
        } else if (type instanceof GenericArrayType array) {
            assertPublicApiType(array.getGenericComponentType(), signature);
        } else if (type instanceof WildcardType wildcard) {
            for (final Type bound : wildcard.getUpperBounds()) assertPublicApiType(bound, signature);
            for (final Type bound : wildcard.getLowerBounds()) assertPublicApiType(bound, signature);
        } else if (type instanceof TypeVariable<?> variable) {
            for (final Type bound : variable.getBounds()) assertPublicApiType(bound, signature);
        }
    }
}
