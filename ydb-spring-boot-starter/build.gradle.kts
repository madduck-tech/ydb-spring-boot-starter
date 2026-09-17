plugins { `java-library` }
val bootVersion = providers.gradleProperty("bootVersion").getOrElse("4.0.8")
tasks.test {
    inputs.property("ydbTestConnection", providers.environmentVariable("YDB_TEST_CONNECTION").orElse(""))
}
dependencies {
    api(project(":ydb-spring-boot"))
    api(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-transaction")
    testImplementation("org.springframework.boot:spring-boot-test")
}
