package io.github.madducktech.ydb.core;

import java.time.Duration;

/** Immutable limits for one template call. Null values inherit template defaults. */
public record YdbQueryOptions(Duration timeout, Long maxRows, Long maxBytes) {
    public static final YdbQueryOptions DEFAULT = new YdbQueryOptions(Duration.ofSeconds(10), 10_000L, 16L * 1024 * 1024);
    public static final YdbQueryOptions INHERIT = new YdbQueryOptions(null, null, null);

    public YdbQueryOptions {
        if (timeout != null && (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofDays(1)) > 0)) {
            throw new IllegalArgumentException("Query timeout must be between 1ms and 1 day");
        }
        if (maxRows != null && maxRows < 1 || maxBytes != null && maxBytes < 1) {
            throw new IllegalArgumentException("Result limits must be positive");
        }
    }

    public YdbQueryOptions withDefaults(YdbQueryOptions defaults) {
        return new YdbQueryOptions(timeout == null ? defaults.timeout : timeout,
                maxRows == null ? defaults.maxRows : maxRows, maxBytes == null ? defaults.maxBytes : maxBytes);
    }
}
