package io.github.madducktech.ydb.boot;

import tech.ydb.query.QueryClient;

/** Customizes a starter-owned client builder after properties; must not call build(). */
@FunctionalInterface
public interface YdbQueryClientBuilderCustomizer {
    void customize(QueryClient.Builder builder);
}
