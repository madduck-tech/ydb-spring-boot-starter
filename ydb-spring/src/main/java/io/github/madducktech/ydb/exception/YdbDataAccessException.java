package io.github.madducktech.ydb.exception;

import org.springframework.dao.UncategorizedDataAccessException;
import tech.ydb.core.StatusCode;

/** An SDK status without a more precise Spring category. */
public final class YdbDataAccessException extends UncategorizedDataAccessException {
    private final StatusCode statusCode;
    public YdbDataAccessException(String phase, StatusCode statusCode) {
        super("YDB " + phase + " failed: " + statusCode.name(), null);
        this.statusCode = statusCode;
    }
    public StatusCode getStatusCode() { return statusCode; }
}
