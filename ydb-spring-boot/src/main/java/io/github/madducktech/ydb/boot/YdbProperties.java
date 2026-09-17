package io.github.madducktech.ydb.boot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import io.github.madducktech.ydb.core.YdbQueryOptions;

/**
 * YDB settings; validate only the active resource creation path.
 * @param enabled whether default YDB integration beans are enabled
 * @param connectionString path-form grpc or grpcs connection URI
 * @param transport transport discovery and TLS settings
 * @param auth authentication selection for a default transport
 * @param sessionPool query client session pool settings
 * @param template synchronous query limits
 * @param transactions Spring transaction settings
 * @param health health probe settings
 */
@ConfigurationProperties("ydb")
public record YdbProperties(
        @DefaultValue("true") boolean enabled,
        String connectionString,
        @DefaultValue Transport transport,
        @DefaultValue Auth auth,
        @DefaultValue SessionPool sessionPool,
        @DefaultValue Template template,
        @DefaultValue Transactions transactions,
        @DefaultValue Health health) {

    public enum AuthMode { ANONYMOUS, TOKEN, ENVIRONMENT }
    public enum InitMode { SYNC, ASYNC }
    public enum TransactionMode { AUTO, ENABLED, DISABLED }
    /**
     * @param initMode whether transport creation waits for discovery
     * @param discoveryTimeout maximum discovery request duration
     * @param tls custom certificate settings
     */
    public record Transport(@DefaultValue("sync") InitMode initMode,
                            @DefaultValue("10s") Duration discoveryTimeout, @DefaultValue Tls tls) { }
    /** @param caCertificate PEM trust certificate using a classpath: or file: resource */
    public record Tls(String caCertificate) { }
    /** @param mode explicit credential selection
     * @param token externally supplied static access token; not logged
     */
    public record Auth(AuthMode mode, String token) {
        @Override public String toString() { return "Auth[mode=" + mode + ", token=<redacted>]"; }
    }
    /** @param minSize minimum session count
     * @param maxSize maximum session count
     * @param maxIdleTime idle session lifetime from 1 second to 30 minutes
     */
    public record SessionPool(@DefaultValue("0") int minSize, @DefaultValue("50") int maxSize,
                              @DefaultValue("5m") Duration maxIdleTime) {
        public void validate() {
            if (minSize < 0 || maxSize < 1 || minSize > maxSize) throw new IllegalArgumentException("ydb.session-pool requires 0 <= min-size <= max-size and max-size > 0");
            if (maxIdleTime.compareTo(Duration.ofSeconds(1)) < 0 || maxIdleTime.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalArgumentException("ydb.session-pool.max-idle-time must be between 1s and 30m");
            }
        }
    }
    /** @param queryTimeout total budget for one template call
     * @param maxResultRows aggregate row limit for materialized results
     * @param maxResultBytes aggregate serialized result-part limit, not a heap limit
     */
    public record Template(@DefaultValue("10s") Duration queryTimeout, @DefaultValue("10000") long maxResultRows,
                           @DefaultValue("16MB") DataSize maxResultBytes) {
        public YdbQueryOptions options() { return new YdbQueryOptions(queryTimeout, maxResultRows, maxResultBytes.toBytes()); }
    }
    /** @param mode default manager creation policy
     * @param defaultTimeout new transaction timeout in whole seconds
     * @param cleanupTimeout rollback request budget after an operation failure
     */
    public record Transactions(@DefaultValue("auto") TransactionMode mode,
                               @DefaultValue("30s") Duration defaultTimeout,
                               @DefaultValue("5s") Duration cleanupTimeout) {
        public int timeoutSeconds() {
            if (defaultTimeout.getNano() != 0 || defaultTimeout.getSeconds() < 1 || defaultTimeout.getSeconds() > 86400) {
                throw new IllegalArgumentException("ydb.transactions.default-timeout must be 1..86400 whole seconds");
            }
            return (int) defaultTimeout.getSeconds();
        }
    }
    /** @param timeout total session acquisition and query budget for a probe */
    public record Health(@DefaultValue("2s") Duration timeout) { }

    static void validateDuration(Duration duration, String property) {
        if (duration.compareTo(Duration.ofMillis(1)) < 0 || duration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(property + " must be between 1ms and 1 day");
        }
    }
    @Override public String toString() { return "YdbProperties[enabled=" + enabled + ", connectionString=<redacted>]"; }
}
