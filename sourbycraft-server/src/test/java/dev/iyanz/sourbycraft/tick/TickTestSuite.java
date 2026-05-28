package dev.iyanz.sourbycraft.tick;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("SourbyCraft tick tests")
@SelectPackages("dev.iyanz.sourbycraft.tick")
public class TickTestSuite {
}
