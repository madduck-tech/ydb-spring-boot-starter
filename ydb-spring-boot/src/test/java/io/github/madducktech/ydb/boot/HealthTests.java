package io.github.madducktech.ydb.boot;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
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
}
