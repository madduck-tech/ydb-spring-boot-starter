package io.github.madducktech.ydb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tech.ydb.core.*;
import tech.ydb.proto.ValueProtos;
import tech.ydb.query.*;
import tech.ydb.query.result.QueryInfo;
import tech.ydb.table.query.Params;
import io.github.madducktech.ydb.core.*;
import io.github.madducktech.ydb.transaction.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransactionTests {
    final QueryClient client = mock(QueryClient.class);
    final List<Session> sessions = new ArrayList<>();
    final YdbTemplate ydb = new YdbTemplate(client);
    final YdbTransactionManager manager = new YdbTransactionManager(client);
    final TransactionTemplate transactions = new TransactionTemplate(manager);

    TransactionTests() {
        when(client.createSession(any())).thenAnswer(call -> {
            Session session = new Session(); sessions.add(session);
            return CompletableFuture.completedFuture(Result.success(session.session));
        });
    }
    @AfterEach void noThreadLeak() {
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
    }
    @Test void standaloneMapsBeforeCommitAndCloses() {
        List<Long> values = ydb.query("SELECT $value", Params.empty(), (row, n) -> {
            assertThat(sessions.get(0).active.get()).isTrue();
            verify(sessions.get(0).tx, never()).commit(any());
            return row.getColumn(0).getInt64();
        });
        assertThat(values).containsExactly(42L);
        verify(sessions.get(0).tx).commit(any());
        verify(sessions.get(0).session).close();
        verify(client, never()).close();
    }
    @Test void callsShareOnePhysicalTransaction() {
        transactions.executeWithoutResult(status -> {
            ydb.execute("UPSERT A", Params.empty());
            ydb.execute("UPSERT B", Params.empty());
            assertThat(sessions).hasSize(1);
            verify(sessions.get(0).tx, never()).commit(any());
        });
        verify(sessions.get(0).tx).commit(any());
        verify(sessions.get(0).session).close();
    }
    @Test void mapperFailureRollsBackWithoutCancellingCompletedStream() {
        assertThatThrownBy(() -> ydb.query("SELECT 42", Params.empty(), (row, n) -> { throw new IllegalArgumentException("mapping"); }))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sessions.get(0).tx).rollback(any());
        verify(sessions.get(0).tx, never()).commit(any());
        verify(sessions.get(0).stream, never()).cancel();
    }
    @Test void caughtParticipatingFailureMakesOuterRollbackOnly() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(outer -> {
            try { transactions.executeWithoutResult(inner -> { throw new IllegalArgumentException("inner"); }); }
            catch (IllegalArgumentException ignored) { }
        })).isInstanceOf(UnexpectedRollbackException.class);
        verify(sessions.get(0).tx).rollback(any());
        verify(sessions.get(0).tx, never()).commit(any());
    }
    @Test void abortedSdkTransactionCannotRestart() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            Session session = sessions.get(0);
            when(session.stream.execute(any())).thenAnswer(call -> {
                session.active.set(false);
                return CompletableFuture.completedFuture(Result.fail(Status.of(StatusCode.ABORTED)));
            });
            assertThatThrownBy(() -> ydb.execute("query", Params.empty())).isInstanceOf(ConcurrencyFailureException.class);
            assertThatThrownBy(() -> ydb.execute("query", Params.empty())).isInstanceOf(UnexpectedRollbackException.class);
        })).isInstanceOf(UnexpectedRollbackException.class);
        verify(sessions.get(0).tx, times(1)).createQuery(anyString(), eq(false), any(), any());
    }
    @Test void defaultAndExplicitSerializableJoin() {
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        transactions.executeWithoutResult(outer -> inner.executeWithoutResult(status -> ydb.execute("query", Params.empty())));
        assertThat(sessions).hasSize(1);
    }
    @Test void unsupportedIsolationFailsBeforeAcquisition() {
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {})).isInstanceOf(InvalidIsolationLevelException.class);
        verifyNoInteractions(client);
    }
    @Test void requiresNewUsesSecondSessionAndRestoresOuter() {
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.executeWithoutResult(outer -> {
            Object bound = TransactionSynchronizationManager.getResource(client);
            inner.executeWithoutResult(status -> ydb.execute("inner", Params.empty()));
            assertThat(TransactionSynchronizationManager.getResource(client)).isSameAs(bound);
            ydb.execute("outer", Params.empty());
        });
        assertThat(sessions).hasSize(2);
        for (Session session : sessions) { verify(session.tx).commit(any()); verify(session.session).close(); }
    }
    @Test void nestedSavepointsAreRejected() {
        TransactionTemplate nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transactions.executeWithoutResult(status -> assertThatThrownBy(() -> nested.executeWithoutResult(s -> {}))
                .isInstanceOf(NestedTransactionNotSupportedException.class));
    }
    @Test void uncertainCommitReportsUnknownAndNeverRollsBackOrRetries() {
        List<Integer> completions = new ArrayList<>();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { completions.add(status); }
            });
            doReturn(CompletableFuture.completedFuture(Result.fail(Status.of(StatusCode.UNDETERMINED)))).when(sessions.get(0).tx).commit(any());
        })).isInstanceOf(YdbCommitOutcomeUnknownException.class);
        assertThat(completions).containsExactly(TransactionSynchronization.STATUS_UNKNOWN);
        verify(sessions.get(0).tx, times(1)).commit(any());
        verify(sessions.get(0).tx, never()).rollback(any());
        verify(sessions.get(0).session).close();
    }
    @Test void cleanupFailureCannotUndoConfirmedCommit() {
        transactions.executeWithoutResult(status -> doThrow(new IllegalStateException("close")).when(sessions.get(0).session).close());
        verify(sessions.get(0).tx).commit(any());
        verify(sessions.get(0).tx, never()).rollback(any());
    }
    @Test void resultLimitCancelsWithoutReturningPartialRows() {
        assertThatThrownBy(() -> ydb.query("query", Params.empty(), new YdbQueryOptions(null, 1L, 1L)))
                .isInstanceOf(DataRetrievalFailureException.class);
        verify(sessions.get(0).stream).cancel();
        verify(sessions.get(0).tx, never()).commit(any());
    }
    @Test void lateAcquisitionIsClosedAfterTimeout() {
        CompletableFuture<Result<QuerySession>> late = new CompletableFuture<>();
        when(client.createSession(any())).thenReturn(late);
        assertThatThrownBy(() -> ydb.execute("query", Params.empty(), new YdbQueryOptions(Duration.ofMillis(20), null, null)))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        QuerySession session = mock(QuerySession.class);
        late.complete(Result.success(session));
        verify(session).close();
        verify(session, never()).beginTransaction(any(), any());
    }
    @Test void inactiveSdkTransactionCannotDispatchAnotherQuery() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            sessions.get(0).active.set(false);
            ydb.execute("must not restart", Params.empty());
        })).isInstanceOf(TransactionSystemException.class);
        verify(sessions.get(0).tx, never()).createQuery(anyString(), anyBoolean(), any(), any());
        verify(sessions.get(0).tx, never()).commit(any());
        verify(sessions.get(0).session).close();
    }
    @Test void lateBeginRollsBackBeforeReleasingSession() {
        Session session = new Session();
        CompletableFuture<Result<QueryTransaction>> begin = new CompletableFuture<>();
        CompletableFuture<Status> rollback = new CompletableFuture<>();
        when(client.createSession(any())).thenReturn(CompletableFuture.completedFuture(Result.success(session.session)));
        when(session.session.beginTransaction(any(), any())).thenReturn(begin);
        doReturn(rollback).when(session.tx).rollback(any());
        assertThatThrownBy(() -> ydb.execute("query", Params.empty(), new YdbQueryOptions(Duration.ofMillis(50), null, null)))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verify(session.session, never()).close();
        begin.complete(Result.success(session.tx));
        verify(session.tx).rollback(any());
        verify(session.session, never()).close();
        rollback.complete(Status.SUCCESS);
        verify(session.session).close();
    }
    @Test void queryTimeoutWaitsForTerminalResponseBeforeCleanup() {
        Session session = new Session();
        CompletableFuture<Result<QueryInfo>> query = new CompletableFuture<>();
        when(client.createSession(any())).thenReturn(CompletableFuture.completedFuture(Result.success(session.session)));
        when(session.stream.execute(any())).thenReturn(query);
        YdbTemplate bounded = new YdbTemplate(client, YdbQueryOptions.DEFAULT, Duration.ofMillis(20));
        assertThatThrownBy(() -> bounded.execute("query", Params.empty(), new YdbQueryOptions(Duration.ofMillis(50), null, null)))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        verify(session.stream).cancel();
        verify(session.tx, never()).rollback(any());
        verify(session.session, never()).close();
        query.complete(Result.success(new QueryInfo(null)));
        verify(session.tx).rollback(any());
        verify(session.tx, never()).commit(any());
        verify(session.session).close();
    }
    @Test void lateCommitRemainsUnknownAndOnlyReleasesSession() {
        Session session = new Session();
        CompletableFuture<Result<QueryInfo>> commit = new CompletableFuture<>();
        when(client.createSession(any())).thenReturn(CompletableFuture.completedFuture(Result.success(session.session)));
        doReturn(commit).when(session.tx).commit(any());
        assertThatThrownBy(() -> ydb.execute("query", Params.empty(), new YdbQueryOptions(Duration.ofMillis(100), null, null)))
                .isInstanceOf(YdbCommitOutcomeUnknownException.class);
        verify(session.session, never()).close();
        commit.complete(Result.success(new QueryInfo(null)));
        verify(session.tx).commit(any());
        verify(session.tx, never()).rollback(any());
        verify(session.session).close();
    }
    @Test void mandatoryAndNeverHaveSpringSemantics() {
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        assertThatThrownBy(() -> transactions.executeWithoutResult(s -> {})).isInstanceOf(IllegalTransactionStateException.class);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        TransactionTemplate never = new TransactionTemplate(manager);
        never.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);
        transactions.executeWithoutResult(s -> assertThatThrownBy(() -> never.executeWithoutResult(n -> {}))
                .isInstanceOf(IllegalTransactionStateException.class));
    }

    @Test void standaloneMapperCannotReenterOrLeakGuard() {
        assertThatThrownBy(() -> ydb.query("query", Params.empty(), (row, n) -> ydb.execute("nested", Params.empty())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Reentrant");
        assertThat(sessions).hasSize(1);
        ydb.execute("next", Params.empty());
        assertThat(sessions).hasSize(2);
    }
    @Test void notSupportedSuspendsOuterAndUsesIndependentCall() {
        TransactionTemplate independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        transactions.executeWithoutResult(outer -> {
            Object holder = TransactionSynchronizationManager.getResource(client);
            independent.executeWithoutResult(s -> ydb.execute("independent", Params.empty()));
            assertThat(TransactionSynchronizationManager.getResource(client)).isSameAs(holder);
        });
        assertThat(sessions).hasSize(2);
        for (Session s : sessions) verify(s.tx).commit(any());
    }
    @Test void readWriteScopeCannotJoinReadOnlyTransaction() {
        transactions.setReadOnly(true);
        TransactionTemplate write = new TransactionTemplate(manager);
        transactions.executeWithoutResult(outer -> assertThatThrownBy(() -> write.executeWithoutResult(inner -> {}))
                .isInstanceOf(IllegalTransactionStateException.class));
    }
    @Test void foreignTransactionCannotSilentlyCommitYdbWrites() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> ydb.execute("query", Params.empty())).isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(client);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void failureToAcquireRequiresNewResumesOuter() {
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.executeWithoutResult(outer -> {
            Object holder = TransactionSynchronizationManager.getResource(client);
            doReturn(CompletableFuture.completedFuture(Result.fail(Status.of(StatusCode.OVERLOADED)))).when(client).createSession(any());
            assertThatThrownBy(() -> inner.executeWithoutResult(s -> {})).isInstanceOf(CannotCreateTransactionException.class);
            assertThat(TransactionSynchronizationManager.getResource(client)).isSameAs(holder);
            ydb.execute("outer", Params.empty());
        });
        verify(sessions.get(0).tx).commit(any());
    }

    static class Session {
        final QuerySession session = mock(QuerySession.class);
        final QueryTransaction tx = mock(QueryTransaction.class);
        final QueryStream stream = mock(QueryStream.class);
        final AtomicBoolean active = new AtomicBoolean(true);
        Session() {
            when(session.beginTransaction(any(), any())).thenReturn(CompletableFuture.completedFuture(Result.success(tx)));
            when(tx.isActive()).thenAnswer(c -> active.get());
            when(tx.createQuery(anyString(), eq(false), any(), any())).thenReturn(stream);
            when(stream.execute(any())).thenAnswer(c -> {
                QueryStream.PartsHandler handler = c.getArgument(0);
                if (handler != null) handler.onNextRawPart(0, row());
                return CompletableFuture.completedFuture(Result.success(new QueryInfo(null)));
            });
            when(tx.commit(any())).thenAnswer(c -> { active.set(false); return CompletableFuture.completedFuture(Result.success(new QueryInfo(null))); });
            when(tx.rollback(any())).thenAnswer(c -> { active.set(false); return CompletableFuture.completedFuture(Status.SUCCESS); });
        }
        static ValueProtos.ResultSet row() {
            return ValueProtos.ResultSet.newBuilder()
                    .addColumns(ValueProtos.Column.newBuilder().setName("value")
                            .setType(ValueProtos.Type.newBuilder().setTypeId(ValueProtos.Type.PrimitiveTypeId.INT64)))
                    .addRows(ValueProtos.Value.newBuilder().addItems(ValueProtos.Value.newBuilder().setInt64Value(42)))
                    .build();
        }
    }
}
