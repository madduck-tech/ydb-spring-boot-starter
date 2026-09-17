package io.github.madducktech.ydb.starter;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.UnexpectedRollbackException;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;
import tech.ydb.table.query.Params;
import tech.ydb.table.values.PrimitiveValue;
import io.github.madducktech.ydb.core.YdbOperations;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "YDB_TEST_CONNECTION", matches = ".+")
class StarterIntegrationTests {
    private ApplicationContextRunner application() {
        return new ApplicationContextRunner().withUserConfiguration(Application.class)
                .withPropertyValues("ydb.connection-string=" + System.getenv("YDB_TEST_CONNECTION"),
                        "ydb.auth.mode=anonymous", "ydb.template.query-timeout=30s", "ydb.transport.discovery-timeout=30s");
    }
    @Test void publishedImportsProvideProxyAndAtomicDatabaseTransactions() {
        application()
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    QueryClient client = context.getBean(QueryClient.class);
                    Accounts accounts = context.getBean(Accounts.class);
                    String table = context.getBean(TableName.class).value;
                    schema(client, "CREATE TABLE `" + table + "` (id Int64 NOT NULL, value Int64, PRIMARY KEY (id));");
                    try {
                        accounts.writePair(false);
                        assertThat(accounts.count()).isEqualTo(2L);
                        accounts.clear();
                        assertThat(accounts.count()).isZero();
                        assertThatThrownBy(() -> accounts.writePair(true)).isInstanceOf(IllegalStateException.class);
                        assertThat(accounts.count()).isZero();
                        assertThatThrownBy(accounts::caughtInnerFailure).isInstanceOf(UnexpectedRollbackException.class);
                        assertThat(accounts.count()).isZero();
                        assertThatThrownBy(accounts::manualCommit).isInstanceOf(RuntimeException.class);
                        assertThat(accounts.count()).isZero();
                        var multiple = context.getBean(YdbOperations.class).query("SELECT 1; SELECT 2;", Params.empty());
                        assertThat(multiple.getResultSetCount()).isEqualTo(2);
                    } finally { schema(client, "DROP TABLE `" + table + "`;"); }
                });
    }
    @Test void requiresNewCommitSurvivesOuterRollback() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            QueryClient client = context.getBean(QueryClient.class);
            Accounts accounts = context.getBean(Accounts.class);
            String table = context.getBean(TableName.class).value;
            schema(client, "CREATE TABLE `" + table + "` (id Int64 NOT NULL, value Int64, PRIMARY KEY (id));");
            try {
                assertThatThrownBy(accounts::outerRollbackWithIndependentCommit).isInstanceOf(IllegalStateException.class);
                assertThat(context.getBean(YdbOperations.class).query("SELECT id FROM `" + table + "`;", Params.empty(),
                        (row, n) -> row.getColumn("id").getInt64())).containsExactly(99L);
            } finally { schema(client, "DROP TABLE `" + table + "`;"); }
        });
    }
    @Test void forbiddenStatementsCannotCommitPriorWrites() {
        application().run(context -> {
            assertThat(context).hasNotFailed();
            QueryClient client = context.getBean(QueryClient.class);
            Accounts accounts = context.getBean(Accounts.class);
            String table = context.getBean(TableName.class).value;
            schema(client, "CREATE TABLE `" + table + "` (id Int64 NOT NULL, value Int64, PRIMARY KEY (id));");
            try {
                for (String statement : new String[] {"ROLLBACK;", "ALTER TABLE `" + table + "` ADD COLUMN forbidden Int64;",
                        "BATCH UPDATE `" + table + "` SET value = 7 WHERE id = 3;"}) {
                    assertThatThrownBy(() -> accounts.writeBeforeControl(statement)).isInstanceOf(RuntimeException.class);
                    assertThat(accounts.count()).isZero();
                }
            } finally { schema(client, "DROP TABLE `" + table + "`;"); }
        });
    }
    static void schema(QueryClient client, String yql) throws Exception {
        var acquired = client.createSession(Duration.ofSeconds(30)).get(30, TimeUnit.SECONDS);
        assertThat(acquired.isSuccess()).as(acquired.getStatus().toString()).isTrue();
        try (QuerySession session = acquired.getValue()) {
            var result = session.createQuery(yql, TxMode.NONE).execute().get(30, TimeUnit.SECONDS);
            assertThat(result.isSuccess()).as(result.getStatus().toString()).isTrue();
        }
    }
    record TableName(String value) { }
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean TableName tableName() { return new TableName("starter_it_" + UUID.randomUUID().toString().replace("-", "")); }
        @Bean Accounts accounts(YdbOperations ydb, TableName table, InnerWrites inner) { return new Accounts(ydb, table.value, inner); }
        @Bean InnerWrites innerWrites(YdbOperations ydb, TableName table) { return new InnerWrites(ydb, table.value); }
    }
    public static class Accounts {
        private final YdbOperations ydb;
        private final String table;
        private final InnerWrites inner;
        Accounts(YdbOperations ydb, String table, InnerWrites inner) { this.ydb = ydb; this.table = table; this.inner = inner; }
        @Transactional(transactionManager = "ydbTransactionManager")
        public void writePair(boolean fail) {
            String query = "DECLARE $id AS Int64; UPSERT INTO `" + table + "` (id, value) VALUES ($id, 42);";
            ydb.execute(query, Params.of("$id", PrimitiveValue.newInt64(1)));
            ydb.execute(query, Params.of("$id", PrimitiveValue.newInt64(2)));
            if (fail) throw new IllegalStateException("Roll back both writes");
        }
        public long count() { return ydb.query("SELECT COUNT(*) AS n FROM `" + table + "`;", Params.empty(), (row, n) -> row.getColumn("n").getUint64()).get(0); }
        @Transactional(transactionManager = "ydbTransactionManager")
        public void manualCommit() {
            writeBeforeControl("COMMIT;");
        }
        @Transactional(transactionManager = "ydbTransactionManager")
        public void writeBeforeControl(String statement) {
            ydb.execute("UPSERT INTO `" + table + "` (id, value) VALUES (3, 42);", Params.empty());
            ydb.execute(statement, Params.empty());
        }
        @Transactional(transactionManager = "ydbTransactionManager")
        public void outerRollbackWithIndependentCommit() {
            ydb.execute("UPSERT INTO `" + table + "` (id, value) VALUES (1, 42);", Params.empty());
            inner.independent();
            throw new IllegalStateException("Roll back outer only");
        }
        @Transactional(transactionManager = "ydbTransactionManager")
        public void caughtInnerFailure() {
            ydb.execute("UPSERT INTO `" + table + "` (id, value) VALUES (1, 42);", Params.empty());
            try { inner.fail(); } catch (IllegalStateException ignored) { }
        }
        public void clear() { ydb.execute("DELETE FROM `" + table + "`;", Params.empty()); }
    }
    public static class InnerWrites {
        private final YdbOperations ydb;
        private final String table;
        InnerWrites(YdbOperations ydb, String table) { this.ydb = ydb; this.table = table; }
        @Transactional(transactionManager = "ydbTransactionManager", propagation = Propagation.REQUIRES_NEW)
        public void independent() { ydb.execute("UPSERT INTO `" + table + "` (id, value) VALUES (99, 42);", Params.empty()); }
        @Transactional(transactionManager = "ydbTransactionManager")
        public void fail() {
            ydb.execute("UPSERT INTO `" + table + "` (id, value) VALUES (2, 42);", Params.empty());
            throw new IllegalStateException("Inner rollback-only");
        }
    }
}
