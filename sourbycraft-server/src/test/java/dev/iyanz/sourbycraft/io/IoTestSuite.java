package dev.iyanz.sourbycraft.io;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("SourbyCraft io tests")
@SelectPackages("dev.iyanz.sourbycraft.io")
public class IoTestSuite {
}
