plugins { `java-library` }
dependencies {
    // Keep the SDK's gRPC transport on the first release fixing CVE-2025-55163.
    // Explicit dependencies also carry the override into Maven consumers.
    api(platform("io.grpc:grpc-bom:1.84.0"))
    api("io.grpc:grpc-netty-shaded:1.84.0")
    api("io.grpc:grpc-protobuf:1.84.0")
    api("io.grpc:grpc-stub:1.84.0")
    api("tech.ydb:ydb-sdk-query:2.4.11")
    api("org.springframework:spring-context:7.0.9")
    api("org.springframework:spring-tx:7.0.9")
}
