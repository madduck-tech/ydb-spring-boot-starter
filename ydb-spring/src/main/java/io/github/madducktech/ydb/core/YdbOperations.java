package io.github.madducktech.ydb.core;

import java.util.List;
import tech.ydb.query.result.QueryInfo;
import tech.ydb.table.query.Params;

/** Synchronous, parameterized YDB operations participating in a matching Spring YDB transaction. */
public interface YdbOperations {
    QueryInfo execute(String yql, Params params, YdbQueryOptions options);
    YdbResult query(String yql, Params params, YdbQueryOptions options);
    <T> List<T> query(String yql, Params params, YdbQueryOptions options, YdbRowMapper<T> mapper);

    default QueryInfo execute(String yql, Params params) { return execute(yql, params, YdbQueryOptions.INHERIT); }
    default YdbResult query(String yql, Params params) { return query(yql, params, YdbQueryOptions.INHERIT); }
    default <T> List<T> query(String yql, Params params, YdbRowMapper<T> mapper) {
        return query(yql, params, YdbQueryOptions.INHERIT, mapper);
    }
}
