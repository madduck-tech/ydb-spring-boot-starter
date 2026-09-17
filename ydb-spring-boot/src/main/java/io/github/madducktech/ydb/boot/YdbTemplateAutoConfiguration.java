package io.github.madducktech.ydb.boot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tech.ydb.query.QueryClient;
import io.github.madducktech.ydb.core.*;

@AutoConfiguration(after = YdbAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ydb", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnSingleCandidate(QueryClient.class)
@EnableConfigurationProperties(YdbProperties.class)
public final class YdbTemplateAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(YdbOperations.class)
    YdbTemplate ydbTemplate(QueryClient client, YdbProperties properties) {
        return new YdbTemplate(client, properties.template().options(), properties.transactions().cleanupTimeout());
    }
}
