plugins { application }
repositories {
    maven {
        url = uri(providers.gradleProperty("starterRepository").getOrElse("../../build/repository"))
        if (url.scheme == "https" && url.host == "maven.pkg.github.com") {
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
        content { includeGroup("io.github.madduck-tech") }
    }
    mavenCentral()
}
val bootVersion = providers.gradleProperty("bootVersion").getOrElse("4.0.8")
val runtimeJava = providers.gradleProperty("runtimeJava").getOrElse("17").toInt()
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    implementation("io.github.madduck-tech:ydb-spring-boot-starter:0.1.1")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(runtimeJava)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
application { mainClass.set("example.DemoApplication") }
