package io.github.madducktech.ydb.internal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import tech.ydb.query.QueryClient;

/** Prevents mapper reentry even for standalone calls without a Spring resource binding. */
public final class OperationGuard implements AutoCloseable {
    private static final ThreadLocal<Set<QueryClient>> ACTIVE = new ThreadLocal<>();
    private final QueryClient client;
    private OperationGuard(QueryClient client) { this.client = client; }

    public static boolean isActive(QueryClient client) {
        Set<QueryClient> active = ACTIVE.get();
        return active != null && active.contains(client);
    }
    public static OperationGuard enter(QueryClient client) {
        if (isActive(client)) throw new IllegalStateException("Reentrant YDB operations from a result mapper are not supported");
        Set<QueryClient> active = ACTIVE.get();
        if (active == null) { active = Collections.newSetFromMap(new IdentityHashMap<>()); ACTIVE.set(active); }
        active.add(client);
        return new OperationGuard(client);
    }
    @Override public void close() {
        Set<QueryClient> active = ACTIVE.get();
        active.remove(client);
        if (active.isEmpty()) ACTIVE.remove();
    }
}
