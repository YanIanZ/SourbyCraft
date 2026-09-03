package dev.iyanz.sourbycraft.perf;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
    TickDataCompatibilityTest.class,
    TickDataBinaryCompatibilityTest.class,
    RegionTickMetricsTest.class,
    RegionTickMetricsConcurrencyTest.class,
    RegionMetricsRegistryTest.class,
    RegionTickMetricsHolderTest.class,
    RegionMetricsLifecycleIntegrationTest.class,
    PerformanceCollectorTest.class,
    MetricsRuntimeTest.class,
    MetricsConsumerTest.class,
    FoliaTickStatisticsTest.class
})
public class SourbyMetricsTestSuite {}
