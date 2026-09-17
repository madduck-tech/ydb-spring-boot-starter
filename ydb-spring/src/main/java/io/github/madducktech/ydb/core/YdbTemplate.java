package io.github.madducktech.ydb.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.ydb.core.Result;
import tech.ydb.query.*;
import tech.ydb.query.result.QueryInfo;
import tech.ydb.query.settings.ExecuteQuerySettings;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import io.github.madducktech.ydb.internal.*;

/** Thread-safe synchronous template; sessions and results are scoped to each operation or transaction. */
public final class YdbTemplate implements YdbOperations {
    private final QueryClient client;
    private final YdbQueryOptions defaults;
    private final Duration cleanupTimeout;

    public YdbTemplate(QueryClient client) { this(client, YdbQueryOptions.DEFAULT, Duration.ofSeconds(5)); }
    public YdbTemplate(QueryClient client, YdbQueryOptions defaults, Duration cleanupTimeout) {
        this.client = Objects.requireNonNull(client);
        this.defaults = Objects.requireNonNull(defaults).withDefaults(YdbQueryOptions.DEFAULT);
        if (cleanupTimeout.compareTo(Duration.ofMillis(1)) < 0 || cleanupTimeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Cleanup timeout must be between 1ms and 1 day");
        }
        this.cleanupTimeout = cleanupTimeout;
    }

    @Override public QueryInfo execute(String yql, Params params, YdbQueryOptions options) {
        return run(yql, params, options, false, YdbResult::getQueryInfo);
    }
    @Override public YdbResult query(String yql, Params params, YdbQueryOptions options) {
        return run(yql, params, options, true, Function.identity());
    }
    @Override public <T> List<T> query(String yql, Params params, YdbQueryOptions options, YdbRowMapper<T> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return run(yql, params, options, true, result -> {
            if (result.getResultSetCount() != 1) throw new InvalidDataAccessApiUsageException("Row mapping requires exactly one result set");
            ResultSetReader rows = result.getResultSet(0);
            List<T> mapped = new ArrayList<>();
            int rowNum = 0;
            while (rows.next()) mapped.add(mapper.mapRow(rows, rowNum++));
            return mapped;
        });
    }

    private <T> T run(String yql, Params params, YdbQueryOptions options, boolean collect, Function<YdbResult, T> mapper) {
        if (yql == null || yql.isBlank()) throw new IllegalArgumentException("YQL must not be blank");
        Objects.requireNonNull(params, "params");
        YdbQueryOptions effective = Objects.requireNonNull(options).withDefaults(defaults);
        try (OperationGuard ignored = OperationGuard.enter(client)) {
            return runInScope(yql, params, effective, collect, mapper);
        }
    }

    private <T> T runInScope(String yql, Params params, YdbQueryOptions effective, boolean collect, Function<YdbResult, T> mapper) {
        Deadline call = Deadline.after(effective.timeout());
        Object bound = TransactionSynchronizationManager.getResource(client);
        if (bound != null && !(bound instanceof TransactionScope)) throw new IllegalTransactionStateException("Unsupported YDB resource bound to this client");
        TransactionScope scope = (TransactionScope) bound;
        boolean owned = scope == null;
        if (owned && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("The active Spring transaction does not manage this YDB client");
        }
        if (owned) scope = TransactionScope.begin(client, call, cleanupTimeout);
        Deadline deadline = call.min(scope.deadline);
        QueryStream stream = null;
        CompletableFuture<?> activeQuery = null;
        boolean entered = false;
        try {
            scope.enter(); entered = true;
            stream = scope.transaction.createQuery(yql, false, params,
                    ExecuteQuerySettings.newBuilder().withRequestTimeout(deadline.remaining()).build());
            ResultCollector collector = collect ? new ResultCollector(effective.maxRows(), effective.maxBytes(), stream) : null;
            var future = stream.execute(collector);
            activeQuery = future;
            scope.pending(future);
            Result<QueryInfo> result = deadline.await(future);
            if (collector != null) collector.check();
            if (!result.isSuccess()) { scope.failed(result.getStatus()); throw Failures.translate("query", result.getStatus()); }
            scope.verifyActive();
            T value = mapper.apply(new YdbResult(collector == null ? List.of() : collector.results(), result.getValue()));
            deadline.remaining();
            if (owned) scope.commit();
            return value;
        } catch (RuntimeException | Error ex) {
            scope.setRollbackOnly();
            if (stream != null && (activeQuery == null || !activeQuery.isDone())) {
                try { stream.cancel(); } catch (RuntimeException cancelFailure) { ex.addSuppressed(cancelFailure); }
            }
            if (owned) {
                try { scope.rollback(); } catch (RuntimeException rollbackFailure) { ex.addSuppressed(rollbackFailure); }
            }
            throw ex;
        } finally {
            if (entered) scope.leave();
            if (owned) scope.finish();
        }
    }
}
