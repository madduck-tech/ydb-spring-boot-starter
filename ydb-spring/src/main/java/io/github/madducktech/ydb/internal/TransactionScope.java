package io.github.madducktech.ydb.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.Result;
import tech.ydb.core.Status;
import tech.ydb.core.StatusCode;
import tech.ydb.query.*;
import tech.ydb.query.result.QueryInfo;
import tech.ydb.query.settings.*;
import io.github.madducktech.ydb.transaction.YdbCommitOutcomeUnknownException;

/** Internal owner of one session and one physical transaction. */
public final class TransactionScope {
    private static final Log LOG = LogFactory.getLog(TransactionScope.class);
    public final QuerySession session;
    public final QueryTransaction transaction;
    public final Deadline deadline;
    private final Duration cleanupTimeout;
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile CompletableFuture<?> pending = CompletableFuture.completedFuture(null);
    private volatile boolean committed;
    private volatile boolean rolledBack;
    private volatile boolean unknown;
    private volatile boolean rollbackDispatched;
    private boolean rollbackOnly;
    private boolean executing;

    private TransactionScope(QuerySession session, QueryTransaction transaction, Deadline deadline, Duration cleanupTimeout) {
        this.session = session;
        this.transaction = transaction;
        this.deadline = deadline;
        this.cleanupTimeout = cleanupTimeout;
    }

    public static TransactionScope begin(QueryClient client, Deadline deadline, Duration cleanupTimeout) {
        CompletableFuture<Result<QuerySession>> acquisition = client.createSession(deadline.remaining());
        QuerySession session;
        try {
            Result<QuerySession> result = deadline.await(acquisition);
            if (!result.isSuccess()) throw Failures.translate("session acquisition", result.getStatus());
            session = result.getValue();
        } catch (RuntimeException ex) {
            acquisition.whenComplete((r, error) -> {
                if (error == null && r != null && r.isSuccess()) close(r.getValue());
            });
            throw ex;
        }
        CompletableFuture<Result<QueryTransaction>> begin;
        try {
            begin = session.beginTransaction(TxMode.SERIALIZABLE_RW,
                    BeginTransactionSettings.newBuilder().withRequestTimeout(deadline.remaining()).build());
        } catch (RuntimeException ex) {
            close(session);
            throw ex;
        }
        try {
            Result<QueryTransaction> result = deadline.await(begin);
            if (!result.isSuccess()) throw Failures.translate("begin", result.getStatus());
            return new TransactionScope(session, result.getValue(), deadline, cleanupTimeout);
        } catch (RuntimeException ex) {
            begin.whenComplete((r, error) -> {
                if (error == null && r != null && r.isSuccess()) rollbackAndClose(session, r.getValue(), cleanupTimeout);
                else close(session);
            });
            throw ex;
        }
    }

    public void enter() {
        if (rollbackOnly || committed || rolledBack || unknown || released.get()) {
            throw new UnexpectedRollbackException("YDB transaction is no longer usable");
        }
        if (executing) throw new IllegalStateException("Overlapping or reentrant YDB template operations are not supported");
        executing = true;
    }
    public void leave() { executing = false; }
    public void pending(CompletableFuture<?> future) { pending = future; }
    public void setRollbackOnly() { rollbackOnly = true; }
    public boolean isRollbackOnly() { return rollbackOnly; }
    public void failed(Status status) {
        rollbackOnly = true;
        if (status.getCode() == StatusCode.ABORTED) rolledBack = true;
    }
    public void verifyActive() {
        if (!transaction.isActive()) {
            rollbackOnly = true;
            unknown = true;
            throw new TransactionSystemException("YDB transaction ended unexpectedly; explicit transaction-control statements are unsupported");
        }
    }

    public void commit() {
        if (rollbackOnly) {
            rollback();
            throw new UnexpectedRollbackException("YDB transaction is rollback-only");
        }
        Duration remaining;
        try { remaining = deadline.remaining(); }
        catch (RuntimeException ex) {
            rollback();
            throw new UnexpectedRollbackException("YDB transaction expired before commit", ex);
        }
        verifyActive();
        Result<QueryInfo> result;
        try {
            CompletableFuture<Result<QueryInfo>> future = transaction.commit(
                    CommitTransactionSettings.newBuilder().withRequestTimeout(remaining).build());
            pending = future;
            result = deadline.await(future);
        } catch (RuntimeException ex) {
            unknown = true;
            throw new YdbCommitOutcomeUnknownException(ex);
        }
        if (result.isSuccess()) { committed = true; return; }
        if (result.getStatus().getCode() == StatusCode.ABORTED) {
            rolledBack = true;
            throw new UnexpectedRollbackException("YDB commit was aborted", Failures.translate("commit", result.getStatus()));
        }
        unknown = true;
        throw new YdbCommitOutcomeUnknownException(Failures.translate("commit", result.getStatus()));
    }

    public void rollback() {
        rollbackOnly = true;
        if (rolledBack) return;
        if (committed || unknown) throw new TransactionSystemException("Cannot confirm rollback of a completed or uncertain YDB transaction");
        Deadline cleanup = Deadline.after(cleanupTimeout);
        try {
            if (!pending.isDone()) cleanup.await(pending);
            if (!transaction.isActive()) throw new TransactionSystemException("YDB transaction has no active ID; rollback cannot be confirmed");
            rollbackDispatched = true;
            CompletableFuture<Status> future = transaction.rollback(
                    RollbackTransactionSettings.newBuilder().withRequestTimeout(cleanup.remaining()).build());
            pending = future;
            Status status = cleanup.await(future);
            if (!status.isSuccess()) throw Failures.translate("rollback", status);
            rolledBack = true;
        } catch (RuntimeException ex) {
            throw new TransactionSystemException("YDB rollback could not be confirmed", ex);
        }
    }

    /** Nonthrowing terminal cleanup; preserve the already reported database outcome. */
    public void finish() {
        if (!released.compareAndSet(false, true)) return;
        pending.whenComplete((ignored, failure) -> {
            if (!committed && !rolledBack && !unknown && !rollbackDispatched && transaction.isActive()) {
                rollbackAndClose(session, transaction, cleanupTimeout);
            } else close(session);
        });
    }

    private static void rollbackAndClose(QuerySession session, QueryTransaction transaction, Duration timeout) {
        try {
            transaction.rollback(RollbackTransactionSettings.newBuilder().withRequestTimeout(timeout).build())
                    .whenComplete((status, error) -> {
                        if (error != null || status == null || !status.isSuccess()) LOG.warn("YDB cleanup rollback could not be confirmed");
                        close(session);
                    });
        } catch (RuntimeException ex) { LOG.warn("YDB cleanup rollback failed"); close(session); }
    }

    private static void close(QuerySession session) {
        try { session.close(); }
        catch (RuntimeException ex) { LOG.warn("YDB session cleanup failed"); }
    }
}
