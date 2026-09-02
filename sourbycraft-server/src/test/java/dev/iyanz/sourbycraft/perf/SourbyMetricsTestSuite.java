package dev.iyanz.sourbycraft.perf;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
    TickDataCompatibilityTest.class,
    TickDataBinaryCompatibilityTest.class,
    RegionTickMetricsTest.class,
    RegionTickMetricsConcurrencyTest.class
})
public class SourbyMetricsTestSuite {}
