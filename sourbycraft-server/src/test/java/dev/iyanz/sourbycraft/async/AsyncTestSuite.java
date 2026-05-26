package dev.iyanz.sourbycraft.async;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

// SourbyCraft - test suite for async framework primitives
@Suite(failIfNoTests = false)
@SuiteDisplayName("SourbyCraft async tests")
@SelectPackages("dev.iyanz.sourbycraft.async")
public class AsyncTestSuite {
}
