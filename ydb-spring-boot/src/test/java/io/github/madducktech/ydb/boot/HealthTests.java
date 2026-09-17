package io.github.madducktech.ydb.boot;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import tech.ydb.core.Result;
import tech.ydb.query.*;
import tech.ydb.query.result.QueryInfo;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HealthTests {
    @Test void probeUsesSdkAndClosesSession() {
        QueryClient client = mock(QueryClient.class);
        QuerySession session = mock(QuerySession.class);
        QueryStream stream = mock(QueryStream.class);
        when(client.createSession(any())).thenReturn(CompletableFuture.completedFuture(Result.success(session)));
        when(session.createQuery(anyString(), any(), any(), any())).thenReturn(stream);
        when(stream.execute()).thenReturn(CompletableFuture.completedFuture(Result.success(new QueryInfo(null))));
        assertThat(new YdbHealthIndicator(client, Duration.ofSeconds(1)).health().getStatus()).isEqualTo(Status.UP);
        verify(session).close();
        verify(client, never()).close();
    }
    @Test void repeatedTimeoutsKeepOneAcquisitionAndCloseLateSession() {
        QueryClient client = mock(QueryClient.class);
        CompletableFuture<Result<QuerySession>> late = new CompletableFuture<>();
        when(client.createSession(any())).thenReturn(late);
        YdbHealthIndicator health = new YdbHealthIndicator(client, Duration.ofMillis(20));
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        verify(client, times(1)).createSession(any());
        QuerySession session = mock(QuerySession.class);
        late.complete(Result.success(session));
        verify(session).close();
        verify(session, never()).createQuery(anyString(), any(), any(), any());
    }
    @Test void cancellationFailureCannotReleaseAnExecutingSession() throws Exception {
        QueryClient client = mock(QueryClient.class);
        QuerySession session = mock(QuerySession.class);
        QueryStream stream = mock(QueryStream.class);
        CompletableFuture<Result<QuerySession>> acquisition = new CompletableFuture<>();
        CompletableFuture<Result<QueryInfo>> query = new CompletableFuture<>();
        CountDownLatch acquiring = new CountDownLatch(1);
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch returnExecution = new CountDownLatch(1);
        when(client.createSession(any())).thenAnswer(call -> { acquiring.countDown(); return acquisition; });
        when(session.createQuery(anyString(), any(), any(), any())).thenReturn(stream);
        when(stream.execute()).thenAnswer(call -> {
            executing.countDown();
            assertThat(returnExecution.await(5, TimeUnit.SECONDS)).isTrue();
            return query;
        });
        doThrow(new IllegalStateException("cancel failed")).when(stream).cancel();
        YdbHealthIndicator health = new YdbHealthIndicator(client, Duration.ofSeconds(1));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var probe = executor.submit(() -> { return health.health(); });
            assertThat(acquiring.await(5, TimeUnit.SECONDS)).isTrue();
            var callback = executor.submit(() -> acquisition.complete(Result.success(session)));
            assertThat(executing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.DOWN);
            returnExecution.countDown();
            callback.get(5, TimeUnit.SECONDS);
            verify(session, never()).close();
            query.complete(Result.success(new QueryInfo(null)));
            verify(session).close();
        } finally {
            returnExecution.countDown();
            executor.shutdownNow();
        }
    }
    @Test void synchronousCancellationCompletionClosesOnlyAfterCancelReturns() {
        QueryClient client = mock(QueryClient.class);
        QuerySession session = mock(QuerySession.class);
        QueryStream stream = mock(QueryStream.class);
        CompletableFuture<Result<QueryInfo>> query = new CompletableFuture<>();
        when(client.createSession(any())).thenReturn(CompletableFuture.completedFuture(Result.success(session)));
        when(session.createQuery(anyString(), any(), any(), any())).thenReturn(stream);
        when(stream.execute()).thenReturn(query);
        doAnswer(call -> {
            query.complete(Result.success(new QueryInfo(null)));
            verify(session, never()).close();
            return null;
        }).when(stream).cancel();
        assertThat(new YdbHealthIndicator(client, Duration.ofMillis(100)).health().getStatus()).isEqualTo(Status.DOWN);
        verify(stream).cancel();
        verify(session).close();
    }
}
