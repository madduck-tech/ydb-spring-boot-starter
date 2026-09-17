package io.github.madducktech.ydb.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.springframework.dao.DataRetrievalFailureException;
import tech.ydb.proto.ValueProtos;
import tech.ydb.query.QueryStream;
import tech.ydb.query.result.QueryResultPart;

/** Collects bounded protobuf parts without invoking application code on SDK threads. */
public final class ResultCollector implements QueryStream.PartsHandler {
    private final long maxRows;
    private final long maxBytes;
    private final QueryStream stream;
    private final TreeMap<Long, ValueProtos.ResultSet.Builder> sets = new TreeMap<>();
    private long rows;
    private long bytes;
    private volatile RuntimeException failure;

    public ResultCollector(long maxRows, long maxBytes, QueryStream stream) {
        this.maxRows = maxRows; this.maxBytes = maxBytes; this.stream = stream;
    }
    @Override public void onNextPart(QueryResultPart part) {
        fail("SDK delivered an unsupported result part representation");
    }
    @Override public synchronized void onNextRawPart(long index, ValueProtos.ResultSet part) {
        if (failure != null) return;
        long size = part.getSerializedSize();
        if (part.getTruncated()) { fail("YDB returned a truncated result set"); return; }
        if (index < 0 || index > 1023) { fail("YDB result-set index exceeds the supported range 0..1023"); return; }
        if (part.getRowsCount() > maxRows - rows || size > maxBytes - bytes) {
            fail("YDB materialized result exceeds configured limits"); return;
        }
        rows += part.getRowsCount(); bytes += size;
        ValueProtos.ResultSet.Builder result = sets.get(index);
        if (result == null) { result = part.toBuilder(); sets.put(index, result); }
        else {
            if (!part.getColumnsList().isEmpty() && !result.getColumnsList().equals(part.getColumnsList())) {
                fail("YDB result metadata changed between parts"); return;
            }
            result.addAllRows(part.getRowsList());
        }
    }
    private void fail(String message) {
        failure = new DataRetrievalFailureException(message);
        stream.cancel();
    }
    public void check() { if (failure != null) throw failure; }
    public synchronized List<ValueProtos.ResultSet> results() {
        check();
        List<ValueProtos.ResultSet> results = new ArrayList<>();
        for (var entry : sets.entrySet()) {
            while (results.size() < entry.getKey()) results.add(ValueProtos.ResultSet.getDefaultInstance());
            results.add(entry.getValue().build());
        }
        return results;
    }
}
