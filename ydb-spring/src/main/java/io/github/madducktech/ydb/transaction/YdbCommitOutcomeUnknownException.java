package io.github.madducktech.ydb.transaction;

import org.springframework.transaction.TransactionSystemException;

/** Commit was dispatched but its result is unknown. Replaying the operation may duplicate effects. */
public final class YdbCommitOutcomeUnknownException extends TransactionSystemException {
    public YdbCommitOutcomeUnknownException(Throwable cause) {
        super("YDB commit outcome is unknown; do not automatically replay the operation", cause);
    }
}
