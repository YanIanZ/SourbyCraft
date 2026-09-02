package dev.iyanz.sourbycraft.api.metrics;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class MetricsApiSurfaceTest {
    @Test
    void apiHasNoServerImplementationTypes() throws NoSuchMethodException {
        assertEquals(PerformanceSnapshot.class, SourbyMetrics.class.getMethod("snapshot").getReturnType());
        for (Class<?> type : new Class<?>[]{SourbyMetrics.class, PerformanceSnapshot.class,
                WindowMetrics.class, RuntimeMetrics.class, Freshness.class}) {
            for (Method method : type.getMethods()) {
                String signature = method.toGenericString();
                assertFalse(signature.contains("net.minecraft"), signature);
                assertFalse(signature.contains("craftbukkit"), signature);
                assertFalse(signature.contains("me.lucko.spark"), signature);
            }
        }
    }

    @Test
    void windowsAreStableAndComplete() {
        assertArrayEquals(new MetricWindow[]{MetricWindow.FIVE_SECONDS, MetricWindow.TEN_SECONDS,
            MetricWindow.ONE_MINUTE, MetricWindow.FIVE_MINUTES, MetricWindow.FIFTEEN_MINUTES},
            MetricWindow.values());
    }
}
