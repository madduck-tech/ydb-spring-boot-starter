package io.github.madducktech.ydb.internal;

import org.springframework.dao.*;
import tech.ydb.core.Status;
import io.github.madducktech.ydb.exception.YdbDataAccessException;

/** Internal status classification; messages deliberately omit queries, credentials and server issues. */
public final class Failures {
    private Failures() { }
    public static DataAccessException translate(String phase, Status status) {
        String message = "YDB " + phase + " failed: " + status.getCode().name();
        YdbDataAccessException cause = new YdbDataAccessException(phase, status.getCode());
        return switch (status.getCode()) {
            case ABORTED -> new ConcurrencyFailureException(message, cause);
            case TIMEOUT, CLIENT_DEADLINE_EXCEEDED, CLIENT_DEADLINE_EXPIRED -> new QueryTimeoutException(message, cause);
            case UNAVAILABLE, TRANSPORT_UNAVAILABLE, CLIENT_DISCOVERY_FAILED,
                    BAD_SESSION, SESSION_EXPIRED -> new DataAccessResourceFailureException(message, cause);
            default -> cause;
        };
    }
}
