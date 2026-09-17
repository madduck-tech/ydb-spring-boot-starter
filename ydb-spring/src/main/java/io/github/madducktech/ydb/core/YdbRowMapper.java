package io.github.madducktech.ydb.core;

import tech.ydb.table.result.ResultSetReader;

/** Maps the current row on the calling thread; do not advance or retain the reader. */
@FunctionalInterface
public interface YdbRowMapper<T> {
    T mapRow(ResultSetReader row, int rowNum);
}
