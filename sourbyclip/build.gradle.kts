plugins {
    java
    application
    `maven-publish`
}

subprojects {
    apply(plugin = "java")

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

val mainClass = "dev.iyanz.sourbyclip.Main"

tasks.jar {
    val java6Jar = project(":java6").tasks.named("jar")
    val java25Jar = project(":java25").tasks.named("shadowJar")
    dependsOn(java6Jar, java25Jar)

    from(zipTree(java6Jar.map { it.outputs.files.singleFile }))
    from(zipTree(java25Jar.map { it.outputs.files.singleFile }))

    manifest {
        attributes(
            "Main-Class" to mainClass,
            "Clip-Version" to project.version
        )
    }

    from(file("license.txt")) {
        into("META-INF/license")
        rename { "paperclip-LICENSE.txt" }
    }
    from(file("license.txt")) {
        into("META-INF/license")
        rename { "sourbyclip-LICENSE.txt" }
    }
    rename { name ->
        if (name.endsWith("-LICENSE.txt")) {
            "META-INF/license/$name"
        } else {
            name
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    val java6Sources = project(":java6").tasks.named("sourcesJar")
    val java25Sources = project(":java25").tasks.named("sourcesJar")
    dependsOn(java6Sources, java25Sources)

    from(zipTree(java6Sources.map { it.outputs.files.singleFile }))
    from(zipTree(java25Sources.map { it.outputs.files.singleFile }))

    archiveClassifier.set("sources")
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])
            artifact(sourcesJar)
            withoutBuildIdentifier()

            pom {
                // SourbyCraft - rebranded from LuminolMC/Hyacinthusclip (MIT). This is the
                // paperclip fork the SourbyCraft slim jar bootstraps into at runtime.
                val repoPath = "iyanz/SourbyCraft"
                val repoUrl = "https://github.com/$repoPath"

                name.set("Sourbyclip")
                description.set(project.description)
                url.set(repoUrl)
                packaging = "jar"

                licenses {
                    license {
                        name.set("MIT")
                        url.set("$repoUrl/blob/main/sourbyclip/license.txt")
                        distribution.set("repo")
                    }
                }

                issueManagement {
                    system.set("GitHub")
                    url.set("$repoUrl/issues")
                }

                developers {
                    developer {
                        id.set("iyanz")
                        name.set("SourbyCraft")
                    }
                    // upstream Paperclip / Hyacinthusclip authors (rebranded fork)
                    developer {
                        id.set("DemonWav")
                        name.set("Kyle Wood")
                        email.set("demonwav@gmail.com")
                        url.set("https://github.com/DemonWav")
                    }
                    developer {
                        id.set("MrHua269")
                        name.set("MrHua269")
                        email.set("mrhua269@gmail.com")
                        url.set("https://github.com/MrHua269")
                    }
                }

                scm {
                    url.set(repoUrl)
                    connection.set("scm:git:$repoUrl.git")
                    developerConnection.set("scm:git:git@github.com:$repoPath.git")
                }
            }
        }

        repositories {
            // SourbyCraft - publish straight into the in-repo maven mirror (sourby-maven/)
            // that the server build resolves the clip from. Override with -PsourbyMavenDir=...
            maven {
                name = "SourbyMaven"
                url = uri(
                    (findProperty("sourbyMavenDir") as String?)
                        ?: rootProject.layout.projectDirectory.dir("../sourby-maven").asFile.absolutePath
                )
            }
        }
    }
}

tasks.register("printVersion") {
    doFirst {
        println(version)
    }
}
