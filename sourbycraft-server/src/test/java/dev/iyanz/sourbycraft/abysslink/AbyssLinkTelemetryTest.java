package dev.iyanz.sourbycraft.abysslink;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.iyanz.sourbycraft.api.metrics.*;
import java.util.Map;
import static dev.iyanz.sourbycraft.abysslink.AbyssLinkTlv.*;

class AbyssLinkTelemetryTest {
    @Test void unavailableIsDegradedAndSaturationIsAdvisoryOverload() {
        assertEquals(1,AbyssLinkTelemetry.health(null,false,false));
        assertEquals(2,AbyssLinkTelemetry.health(null,true,false));
        var snapshot=mock(PerformanceSnapshot.class);var freshness=mock(Freshness.class);var runtime=mock(RuntimeMetrics.class);
        when(snapshot.freshness()).thenReturn(freshness);when(snapshot.runtime()).thenReturn(runtime);
        when(freshness.state()).thenReturn(MetricState.STALE);
        assertEquals(1,AbyssLinkTelemetry.health(snapshot,false,false));
        when(freshness.state()).thenReturn(MetricState.AVAILABLE);
        assertEquals(0,AbyssLinkTelemetry.health(snapshot,false,false));
        when(runtime.processCpuPercent()).thenReturn(96.0);
        assertEquals(2,AbyssLinkTelemetry.health(snapshot,false,false));
    }
    @Test void threatValidationRejectsUnsanitizedDetailsWithoutPolicyMutation() {
        var fields=Map.of(1,value("PROXY_ATTACK_STARTED"),2,u8(5),3,value(123L),4,value("observation"));
        assertDoesNotThrow(() -> AbyssLinkSecurityBridge.validate(fields));
        assertThrows(IllegalArgumentException.class,() -> AbyssLinkSecurityBridge.validate(Map.of(1,value("BAD"),2,u8(11),3,value(123L))));
        assertThrows(IllegalArgumentException.class,() -> AbyssLinkSecurityBridge.validate(Map.of(1,value("BAD"),2,u8(1),3,value(123L),4,value("line\ncommand"))));
    }
}
