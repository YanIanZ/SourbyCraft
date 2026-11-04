// Example plugin for docs/guides/developing-with-sourbyapi.md. Opt-in like test-plugin: it is part of the
// build only with -PincludeTestPlugin=true (what CI passes). The server provides sourbyapi at runtime, so it
// is compileOnly and never shaded.
version = "1.0.0"

repositories {
    maven("https://libraries.minecraft.net/")
}

dependencies {
    findProject(":sourbyapi")?.also { compileOnly(it) }
}

tasks.processResources {
    val properties = mapOf(
        "version" to project.version,
        "api_version" to rootProject.providers.gradleProperty("mcVersion").get()
    )
    inputs.properties(properties)
    filesMatching("plugin.yml") {
        expand(properties)
    }
}
