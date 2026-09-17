package io.github.madducktech.ydb.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.ydb.query.QueryClient;

@AutoConfiguration(after = YdbAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnSingleCandidate(QueryClient.class)
@ConditionalOnProperty(prefix = "ydb", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(YdbProperties.class)
public final class YdbHealthAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEnabledHealthIndicator("ydb")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "ydbHealthIndicator")
        HealthIndicator ydbHealthIndicator(QueryClient client, YdbProperties properties) {
            YdbProperties.validateDuration(properties.health().timeout(), "ydb.health.timeout");
            return new YdbHealthIndicator(client, properties.health().timeout());
        }
    }
}
