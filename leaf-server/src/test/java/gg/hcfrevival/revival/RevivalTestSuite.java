package gg.hcfrevival.revival;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/** Every {@link RevivalTest} under {@code gg.hcfrevival.revival}. Run with {@code ./gradlew :leaf-server:runRevivalTests}. */
@Suite
@SuiteDisplayName("RevivalPaper tests")
@SelectPackages("gg.hcfrevival.revival")
@IncludeTags("RevivalTest")
@ConfigurationParameter(key = "TestSuite", value = "RevivalTest")
public class RevivalTestSuite {
}
