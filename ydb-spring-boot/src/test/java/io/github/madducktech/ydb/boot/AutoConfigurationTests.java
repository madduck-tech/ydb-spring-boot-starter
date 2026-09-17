package io.github.madducktech.ydb.boot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.*;
import org.springframework.transaction.PlatformTransactionManager;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.query.QueryClient;
import io.github.madducktech.ydb.core.*;
import io.github.madducktech.ydb.transaction.YdbTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoConfigurationTests {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
            YdbAutoConfiguration.class, YdbTemplateAutoConfiguration.class, YdbTransactionAutoConfiguration.class,
            YdbHealthAutoConfiguration.class, TransactionManagerCustomizationAutoConfiguration.class, TransactionAutoConfiguration.class));

    @Test void userClientSkipsConnectionAndCreatesHigherLevelBeans() {
        runner.withBean(QueryClient.class, () -> mock(QueryClient.class)).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(YdbTemplate.class).hasSingleBean(YdbTransactionManager.class);
            assertThat(context).doesNotHaveBean(GrpcTransport.class);
            assertThat(context.getBean(YdbProperties.class).sessionPool().maxSize()).isEqualTo(50);
        });
    }
    @Test void disabledNeedsNoConfiguration() {
        runner.withPropertyValues("ydb.enabled=false").run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(QueryClient.class).doesNotHaveBean(YdbOperations.class).doesNotHaveBean(YdbTransactionManager.class));
    }
    @Test void missingConnectionFailsClearlyBeforeNetwork() {
        runner.run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("ydb.connection-string"));
    }
    @Test void invalidPoolFailsBeforeConnection() {
        runner.withPropertyValues("ydb.session-pool.min-size=10", "ydb.session-pool.max-size=2")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("ydb.session-pool"));
    }
    @Test void optionalHealthCanBeAbsent() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.boot.health"))
                .withBean(QueryClient.class, () -> mock(QueryClient.class))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(YdbOperations.class).doesNotHaveBean("ydbHealthIndicator"));
    }
    @Test void healthToggleIsHonored() {
        runner.withBean(QueryClient.class, () -> mock(QueryClient.class)).withPropertyValues("management.health.ydb.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("ydbHealthIndicator"));
    }
    @Test void existingManagerPreservesSelection() {
        runner.withBean(QueryClient.class, () -> mock(QueryClient.class))
                .withBean("foreignManager", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(YdbTransactionManager.class).hasSingleBean(YdbTemplate.class));
    }
    @Test void enabledCanCreateAlongsideForeignManager() {
        runner.withBean(QueryClient.class, () -> mock(QueryClient.class))
                .withBean("foreignManager", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withPropertyValues("ydb.transactions.mode=enabled")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(YdbTransactionManager.class));
    }
    @Test void unsafeGlobalCommitSettingIsRejected() {
        runner.withBean(QueryClient.class, () -> mock(QueryClient.class)).withPropertyValues("spring.transaction.rollback-on-commit-failure=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("rollback-on-commit-failure"));
    }
    @Test void globalTimeoutHasDocumentedPrecedence() {
        runner.withBean(QueryClient.class, () -> mock(QueryClient.class))
                .withPropertyValues("ydb.transactions.default-timeout=20s", "spring.transaction.default-timeout=40s")
                .run(context -> assertThat(context.getBean(YdbTransactionManager.class).getDefaultTimeout()).isEqualTo(40));
    }
    @Test void customTemplateBacksOffIndependently() {
        runner.withBean(QueryClient.class, () -> mock(QueryClient.class)).withBean(YdbOperations.class, () -> mock(YdbOperations.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(YdbTemplate.class).hasSingleBean(YdbTransactionManager.class));
    }
}
