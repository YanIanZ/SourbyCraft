if (providers.gradleProperty("includeTestPlugin").map(String::toBoolean).getOrElse(false)) {
    include(":test-plugin")
}
