plugins { `java-library` }
val bootVersion = providers.gradleProperty("bootVersion").getOrElse("4.0.8")
dependencies {
    api(project(":ydb-spring"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-transaction")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$bootVersion")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor:$bootVersion")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-health")
}
