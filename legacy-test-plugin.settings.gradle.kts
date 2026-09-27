// A legacy Bukkit plugin (no folia-supported) that CI boots with aurora.bridge.mode = "safe".
if (providers.gradleProperty("includeTestPlugin").map(String::toBoolean).getOrElse(false)) {
    include(":legacy-test-plugin")
}
