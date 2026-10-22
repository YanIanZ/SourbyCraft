package dev.iyanz.aurora.engine.configuration;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Canvas configuration tests")
@IncludeTags("Configuration")
@SelectPackages("dev.iyanz.aurora.engine.configuration")
@ExcludeClassNamePatterns(".*TestSuite")
@ConfigurationParameter(key = "TestSuite", value = "Configuration")
public class CanvasConfigurationDocumentationTestSuite {
}
