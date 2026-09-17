package io.github.madducktech.ydb.boot;

import tech.ydb.core.grpc.GrpcTransportBuilder;

/** Customizes a starter-owned builder after properties; must not call build(). */
@FunctionalInterface
public interface YdbTransportBuilderCustomizer {
    void customize(GrpcTransportBuilder builder);
}
