package io.github.madducktech.ydb.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;

/** Internal monotonic deadline; no wall-clock arithmetic. */
public final class Deadline {
    private final long end;
    private Deadline(long end) { this.end = end; }
    public static Deadline after(Duration duration) {
        if (duration.isNegative() || duration.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Deadline must be between zero and 365 days");
        }
        return new Deadline(System.nanoTime() + duration.toNanos());
    }
    public Deadline min(Deadline other) { return remainingNanos() < other.remainingNanos() ? this : other; }
    private long remainingNanos() { return end - System.nanoTime(); }
    public Duration remaining() {
        long nanos = remainingNanos();
        if (nanos < 1_000_000) throw new QueryTimeoutException("YDB operation deadline expired");
        return Duration.ofNanos(nanos);
    }
    public <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw new QueryTimeoutException("YDB operation deadline expired", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DataAccessResourceFailureException("YDB operation interrupted", ex);
        } catch (ExecutionException ex) {
            throw new DataAccessResourceFailureException("YDB SDK operation failed", ex.getCause());
        }
    }
}
