package io.github.madducktech.ydb.transaction;

import java.time.Duration;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tech.ydb.query.QueryClient;
import io.github.madducktech.ydb.internal.Deadline;
import io.github.madducktech.ydb.internal.TransactionScope;
import io.github.madducktech.ydb.internal.OperationGuard;

/** Imperative transaction manager for templates sharing the same QueryClient instance. */
public final class YdbTransactionManager extends AbstractPlatformTransactionManager {
    private final QueryClient client;
    private final Duration cleanupTimeout;

    public YdbTransactionManager(QueryClient client) { this(client, Duration.ofSeconds(5)); }
    public YdbTransactionManager(QueryClient client, Duration cleanupTimeout) {
        this.client = Objects.requireNonNull(client);
        if (cleanupTimeout.compareTo(Duration.ofMillis(1)) < 0 || cleanupTimeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Cleanup timeout must be between 1ms and 1 day");
        }
        this.cleanupTimeout = cleanupTimeout;
        setDefaultTimeout(30);
        setValidateExistingTransaction(true);
        setGlobalRollbackOnParticipationFailure(true);
        setNestedTransactionAllowed(false);
        setRollbackOnCommitFailure(false);
    }

    /** Validate settings after customization and before sharing the manager. */
    public void validateConfiguration() {
        if (isNestedTransactionAllowed() || !isValidateExistingTransaction() || !isGlobalRollbackOnParticipationFailure()
                || isRollbackOnCommitFailure() || getDefaultTimeout() <= 0 || getDefaultTimeout() > 86400
                || getTransactionSynchronization() == SYNCHRONIZATION_NEVER) {
            throw new IllegalArgumentException("Unsupported YDB transaction-manager configuration: require validation and rollback-only propagation, "
                    + "disable nested savepoints and rollback-on-commit-failure, enable synchronization, and use a 1..86400 second default timeout");
        }
    }

    @Override protected Object doGetTransaction() {
        validateConfiguration();
        Object existing = TransactionSynchronizationManager.getResource(client);
        if (existing != null && !(existing instanceof TransactionScope)) throw new IllegalTransactionStateException("Unexpected resource bound to YDB client");
        return new TransactionObject((TransactionScope) existing);
    }
    @Override protected boolean isExistingTransaction(Object transaction) { return object(transaction).scope != null; }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
        if (OperationGuard.isActive(client)) throw new IllegalTransactionStateException("Cannot begin a Spring transaction from a YDB result mapper");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("An active foreign transaction cannot enlist YDB");
        }
        if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT
                && definition.getIsolationLevel() != TransactionDefinition.ISOLATION_SERIALIZABLE) {
            throw new InvalidIsolationLevelException("YDB supports DEFAULT and SERIALIZABLE isolation in this release");
        }
        int timeout = determineTimeout(definition);
        if (timeout < 0 || timeout > 86400) throw new InvalidTimeoutException("YDB timeout must be 0..86400 seconds", timeout);
        try {
            TransactionScope scope = TransactionScope.begin(client, Deadline.after(Duration.ofSeconds(timeout)), cleanupTimeout);
            try { TransactionSynchronizationManager.bindResource(client, scope); }
            catch (RuntimeException ex) { scope.finish(); throw ex; }
            object(transaction).scope = scope;
        } catch (RuntimeException ex) {
            throw new CannotCreateTransactionException("Could not begin YDB transaction", ex);
        }
    }
    @Override protected void prepareSynchronization(DefaultTransactionStatus status, TransactionDefinition definition) {
        super.prepareSynchronization(status, definition);
        if (status.isNewSynchronization() && status.hasTransaction()) {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        }
    }
    @Override protected Object doSuspend(Object transaction) {
        TransactionObject tx = object(transaction);
        TransactionScope scope = tx.scope;
        TransactionSynchronizationManager.unbindResource(client);
        tx.scope = null;
        return scope;
    }
    @Override protected void doResume(Object transaction, Object suspendedResources) {
        TransactionScope scope = (TransactionScope) suspendedResources;
        if (transaction != null) object(transaction).scope = scope;
        TransactionSynchronizationManager.bindResource(client, scope);
    }
    @Override protected void doCommit(DefaultTransactionStatus status) { object(status.getTransaction()).scope.commit(); }
    @Override protected void doRollback(DefaultTransactionStatus status) { object(status.getTransaction()).scope.rollback(); }
    @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { object(status.getTransaction()).scope.setRollbackOnly(); }
    @Override protected void doCleanupAfterCompletion(Object transaction) {
        TransactionObject tx = object(transaction);
        TransactionScope scope = tx.scope;
        if (scope != null && TransactionSynchronizationManager.getResource(client) == scope) TransactionSynchronizationManager.unbindResource(client);
        tx.scope = null;
        if (scope != null) scope.finish();
    }
    private static TransactionObject object(Object value) { return (TransactionObject) value; }
    private static final class TransactionObject implements SmartTransactionObject {
        private TransactionScope scope;
        private TransactionObject(TransactionScope scope) { this.scope = scope; }
        @Override public boolean isRollbackOnly() { return scope != null && scope.isRollbackOnly(); }
    }
}
