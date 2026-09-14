package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.entity.ScratchBufferConfinementTest;
import dev.iyanz.sourbycraft.util.IoLifecycleTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({RuntimeSamplerTest.class, IoLifecycleTest.class, ConfigSnapshotTest.class,
    TelemetryEventTest.class, AsyncPathShutdownTest.class, AsyncPathCompletionTest.class,
    ScratchBufferConfinementTest.class})
public class RuntimeModernizationTestSuite {}
