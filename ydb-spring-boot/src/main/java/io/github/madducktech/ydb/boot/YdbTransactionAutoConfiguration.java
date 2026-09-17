package io.github.madducktech.ydb.boot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.transaction.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionManager;
import tech.ydb.query.QueryClient;
import io.github.madducktech.ydb.transaction.YdbTransactionManager;

@AutoConfiguration(after = {YdbAutoConfiguration.class, TransactionManagerCustomizationAutoConfiguration.class},
        before = TransactionAutoConfiguration.class,
        afterName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "org.springframework.boot.r2dbc.autoconfigure.R2dbcTransactionManagerAutoConfiguration",
                "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration"})
@ConditionalOnProperty(prefix = "ydb", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnSingleCandidate(QueryClient.class)
@ConditionalOnMissingBean(YdbTransactionManager.class)
@EnableConfigurationProperties(YdbProperties.class)
public final class YdbTransactionAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ydb.transactions", name = "mode", havingValue = "auto", matchIfMissing = true)
    @ConditionalOnMissingBean(TransactionManager.class)
    static class Automatic {
        @Bean YdbTransactionManager ydbTransactionManager(QueryClient client, YdbProperties properties,
                                                        ObjectProvider<TransactionManagerCustomizers> customizers) {
            return create(client, properties, customizers);
        }
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ydb.transactions", name = "mode", havingValue = "enabled")
    static class Enabled {
        @Bean YdbTransactionManager ydbTransactionManager(QueryClient client, YdbProperties properties,
                                                        ObjectProvider<TransactionManagerCustomizers> customizers) {
            return create(client, properties, customizers);
        }
    }
    private static YdbTransactionManager create(QueryClient client, YdbProperties properties,
                                                 ObjectProvider<TransactionManagerCustomizers> customizers) {
        YdbTransactionManager manager = new YdbTransactionManager(client, properties.transactions().cleanupTimeout());
        manager.setDefaultTimeout(properties.transactions().timeoutSeconds());
        customizers.ifAvailable(c -> c.customize(manager));
        manager.validateConfiguration();
        return manager;
    }
}
