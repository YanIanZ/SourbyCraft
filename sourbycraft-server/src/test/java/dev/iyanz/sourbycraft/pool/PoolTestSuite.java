package dev.iyanz.sourbycraft.pool;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

// SourbyCraft - test suite for ThreadLocal object pool classes
@Suite(failIfNoTests = false)
@SuiteDisplayName("SourbyCraft pool tests")
@SelectPackages("dev.iyanz.sourbycraft.pool")
public class PoolTestSuite {
}
