package dev.iyanz.sourbycraft.entity;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/** Ensures the server's suite-only Gradle discovery runs Task D regression checks. */
@Suite
@SelectClasses({TrackerBroadcastTest.class, BlockCollisionBoundsTest.class})
public class EntityOptimizationTestSuite {
}
