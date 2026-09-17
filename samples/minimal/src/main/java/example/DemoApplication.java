package example;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.query.QueryClient;
import tech.ydb.query.QuerySession;
import tech.ydb.table.query.Params;
import tech.ydb.table.values.PrimitiveValue;
import io.github.madducktech.ydb.core.YdbOperations;

/** Standalone consumer: creates a temporary table, checks commit/rollback, then removes the table. */
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(DemoApplication.class).web(WebApplicationType.NONE).run(args)) {
            Table table = context.getBean(Table.class);
            QueryClient client = context.getBean(QueryClient.class);
            Accounts accounts = context.getBean(Accounts.class);
            schema(client, "CREATE TABLE `" + table.name + "` (id Int64 NOT NULL, value Int64, PRIMARY KEY (id));");
            try {
                accounts.writePair(false);
                require(accounts.count() == 2, "Both writes must commit");
                accounts.clear();
                try { accounts.writePair(true); throw new AssertionError("Expected application failure"); }
                catch (PlannedRollback expected) { }
                require(accounts.count() == 0, "Both writes must roll back");
                System.out.println("YDB starter: commit and rollback verified on Java " + Runtime.version().feature());
            } finally { schema(client, "DROP TABLE `" + table.name + "`;"); }
        }
    }
    @Bean Table table() { return new Table("starter_sample_" + UUID.randomUUID().toString().replace("-", "")); }
    @Bean Accounts accounts(YdbOperations ydb, Table table) { return new Accounts(ydb, table.name); }
    record Table(String name) { }
    public static final class PlannedRollback extends RuntimeException { }
    public static class Accounts {
        private final YdbOperations ydb;
        private final String table;
        Accounts(YdbOperations ydb, String table) { this.ydb = ydb; this.table = table; }
        @Transactional(transactionManager = "ydbTransactionManager")
        public void writePair(boolean fail) {
            String query = "DECLARE $id AS Int64; UPSERT INTO `" + table + "` (id, value) VALUES ($id, 42);";
            ydb.execute(query, Params.of("$id", PrimitiveValue.newInt64(1)));
            ydb.execute(query, Params.of("$id", PrimitiveValue.newInt64(2)));
            if (fail) throw new PlannedRollback();
        }
        public long count() { return ydb.query("SELECT COUNT(*) AS n FROM `" + table + "`;", Params.empty(), (row, index) -> row.getColumn("n").getUint64()).get(0); }
        public void clear() { ydb.execute("DELETE FROM `" + table + "`;", Params.empty()); }
    }
    private static void schema(QueryClient client, String query) throws Exception {
        var acquired = client.createSession(Duration.ofSeconds(30)).get(30, TimeUnit.SECONDS);
        require(acquired.isSuccess(), "Schema session: " + acquired.getStatus().getCode());
        try (QuerySession session = acquired.getValue()) {
            var result = session.createQuery(query, TxMode.NONE).execute().get(30, TimeUnit.SECONDS);
            require(result.isSuccess(), "Schema query: " + result.getStatus().getCode());
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
