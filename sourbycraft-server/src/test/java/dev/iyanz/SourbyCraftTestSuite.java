package dev.iyanz;

import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Every SourbyCraft and Aurora test class.
 *
 * <p>The server's test task only runs classes named {@code *TestSuite} (Paper's convention, so its
 * registry-dependent tests run inside their suites). Tests registered one by one in the
 * area suites therefore ran, and any test nobody added to a suite never did: on 2026-10-06, 39 of
 * 80 test classes here had never been executed, including every Aurora World Fabric test the
 * AWF documentation cites. Selecting the packages makes a new test class run without anyone
 * having to remember to list it. The area suites are excluded so nothing runs twice from here.</p>
 */
@Suite
@SelectPackages({"dev.iyanz.sourbycraft", "dev.iyanz.aurora"})
@ExcludeClassNamePatterns(".*TestSuite$")
public class SourbyCraftTestSuite {
}
