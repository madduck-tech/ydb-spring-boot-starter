package io.github.madducktech.ydb.core;

import java.util.List;
import tech.ydb.proto.ValueProtos;
import tech.ydb.query.result.QueryInfo;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.result.impl.ProtoValueReaders;

/** Detached materialized results. Each reader request creates an independent mutable cursor. */
public final class YdbResult {
    private final List<ValueProtos.ResultSet> results;
    private final QueryInfo queryInfo;

    public YdbResult(List<ValueProtos.ResultSet> results, QueryInfo queryInfo) {
        this.results = List.copyOf(results);
        this.queryInfo = queryInfo;
    }

    public int getResultSetCount() { return results.size(); }
    public ResultSetReader getResultSet(int index) { return ProtoValueReaders.forResultSet(results.get(index)); }
    public QueryInfo getQueryInfo() { return queryInfo; }
}
