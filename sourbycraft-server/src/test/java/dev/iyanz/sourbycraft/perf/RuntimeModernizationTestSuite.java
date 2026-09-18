package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.brand.EngineNameTest;
import dev.iyanz.sourbycraft.entity.IsInWallBoundsTest;
import dev.iyanz.sourbycraft.entity.ScratchBufferConfinementTest;
import dev.iyanz.sourbycraft.util.IoLifecycleTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({RuntimeSamplerTest.class, IoLifecycleTest.class, ConfigSnapshotTest.class,
    dev.iyanz.sourbycraft.config.AuroraConfigTest.class,
    TelemetryEventTest.class, AsyncPathShutdownTest.class, AsyncPathCompletionTest.class,
    ScratchBufferConfinementTest.class, IsInWallBoundsTest.class, EngineNameTest.class,
    dev.iyanz.sourbycraft.execution.ExecutionLaneTest.class,
    dev.iyanz.sourbycraft.execution.region.AuroraRegionTest.class,
    dev.iyanz.sourbycraft.execution.LaneCpuSamplerTest.class,
    dev.iyanz.sourbycraft.execution.LanePortionsTest.class,
    dev.iyanz.sourbycraft.command.SourbyReplyTest.class,
    dev.iyanz.sourbycraft.command.PerfLanesViewTest.class,
    dev.iyanz.sourbycraft.brand.AuroraBootTest.class,
    dev.iyanz.sourbycraft.command.SpecCommandTest.class,
    dev.iyanz.sourbycraft.brand.GcAdvisorHeadroomTest.class,
    dev.iyanz.sourbycraft.core.AuroraRuntimeTest.class,
    dev.iyanz.sourbycraft.config.upstream.UpstreamConfigBridgeTest.class,
    dev.iyanz.sourbycraft.SourbyCraftConfigShadowTest.class})
public class RuntimeModernizationTestSuite {}
