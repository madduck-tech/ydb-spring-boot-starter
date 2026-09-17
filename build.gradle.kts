plugins { base }

val bootVersion = providers.gradleProperty("bootVersion").getOrElse("4.0.8")
val testJava = providers.gradleProperty("testJava").getOrElse("17").toInt()

allprojects {
    group = "io.github.madduck-tech"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        withSourcesJar()
        withJavadocJar()
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.encoding = "UTF-8"
    }
    tasks.withType<Javadoc>().configureEach {
        exclude("**/internal/**")
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
    }
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    val toolchains = extensions.getByType<JavaToolchainService>()
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(testJava)) })
        testLogging { events("failed", "skipped") }
    }
    dependencies {
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testImplementation"("org.mockito:mockito-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("library") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Native YDB SDK integration with Spring and Spring Boot")
                    url.set("https://github.com/madduck-tech/ydb-spring-boot-starter")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    scm {
                        url.set("https://github.com/madduck-tech/ydb-spring-boot-starter")
                        connection.set("scm:git:https://github.com/madduck-tech/ydb-spring-boot-starter.git")
                    }
                    developers {
                        developer {
                            id.set("madduck-tech")
                            name.set("Madduck Tech contributors")
                            url.set("https://github.com/madduck-tech")
                        }
                    }
                }
            }
        }
        repositories { maven { name = "buildRepository"; url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI() } }
    }
}

tasks.wrapper { gradleVersion = "9.5.1"; distributionType = Wrapper.DistributionType.BIN }
