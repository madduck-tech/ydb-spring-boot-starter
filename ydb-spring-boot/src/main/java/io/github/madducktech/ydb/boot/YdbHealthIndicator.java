package io.github.madducktech.ydb.boot;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.query.*;
import tech.ydb.query.settings.ExecuteQuerySettings;
import tech.ydb.table.query.Params;
import io.github.madducktech.ydb.internal.Deadline;

/** A single-flight SDK probe independent of the caller's Spring transaction. */
final class YdbHealthIndicator implements HealthIndicator {
    private final QueryClient client;
    private final Duration timeout;
    private Flight flight;
    YdbHealthIndicator(QueryClient client, Duration timeout) { this.client = client; this.timeout = timeout; }

    @Override public Health health() {
        Flight current;
        synchronized (this) {
            if (flight == null || flight.result.isDone()) { flight = new Flight(); flight.start(); }
            current = flight;
        }
        try { return current.deadline.await(current.result); }
        catch (RuntimeException ex) {
            current.expired.set(true);
            current.cancel();
            return Health.down().withDetail("reason", "probe unavailable or timed out").build();
        }
    }

    private final class Flight {
        final Deadline deadline = Deadline.after(timeout);
        final CompletableFuture<Health> result = new CompletableFuture<>();
        final AtomicBoolean expired = new AtomicBoolean();
        final AtomicReference<QueryStream> stream = new AtomicReference<>();
        private boolean cancelling;
        private Runnable deferredCompletion;

        synchronized void cancel() {
            QueryStream query = stream.get();
            if (query == null) return;
            cancelling = true;
            try { query.cancel(); } catch (RuntimeException ignored) { }
            finally {
                cancelling = false;
                if (deferredCompletion != null) {
                    Runnable completion = deferredCompletion;
                    deferredCompletion = null;
                    completion.run();
                }
            }
        }

        synchronized void completed(QuerySession session, boolean success) {
            stream.set(null);
            Runnable completion = () -> {
                boolean cleaned = close(session);
                if (success && cleaned && !expired.get()) result.complete(Health.up().build()); else down();
            };
            // SDK cancellation may synchronously complete the future. Do not recycle
            // its session until cancel() has returned, or cancel an already recycled session.
            if (cancelling) deferredCompletion = completion;
            else completion.run();
        }

        void start() {
            try {
                client.createSession(deadline.remaining()).whenComplete((acquired, acquisitionError) -> {
                    if (acquisitionError != null || acquired == null || !acquired.isSuccess()) { down(); return; }
                    QuerySession session = acquired.getValue();
                    try {
                        if (expired.get()) { close(session); down(); return; }
                        QueryStream query = session.createQuery("SELECT 1;", TxMode.SERIALIZABLE_RW, Params.empty(),
                                ExecuteQuerySettings.newBuilder().withRequestTimeout(deadline.remaining()).build());
                        stream.set(query);
                        var execution = query.execute();
                        execution.whenComplete((response, queryError) -> {
                            completed(session, queryError == null && response != null && response.isSuccess());
                        });
                        if (expired.get()) cancel();
                    } catch (RuntimeException ex) { close(session); down(); }
                });
            } catch (RuntimeException ex) { down(); }
        }
        void down() { result.complete(Health.down().withDetail("reason", "probe failed").build()); }
        boolean close(QuerySession session) {
            try { session.close(); return true; } catch (RuntimeException ex) { return false; }
        }
    }
}
