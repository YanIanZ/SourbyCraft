package dev.iyanz.sourbycraft.awf.multiprofile;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * AWF MultiProfile and Aurora Redis Fabric tests, runnable as one area suite:
 * {@code ./gradlew :sourbycraft-server:test --tests 'dev.iyanz.sourbycraft.awf.multiprofile.AwfMultiProfileTestSuite'}.
 *
 * <p>The main {@code SourbyCraftTestSuite} also selects these packages, so nothing here escapes the
 * full run; this suite exists so the area can be exercised without the whole server suite.</p>
 */
@Suite
@SelectPackages({"dev.iyanz.sourbycraft.awf.multiprofile", "dev.iyanz.sourbycraft.awf.redis.fabric"})
public class AwfMultiProfileTestSuite {
}
