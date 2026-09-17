package io.github.madducktech.ydb.boot;

import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.NopAuthProvider;
import tech.ydb.auth.TokenAuthProvider;
import tech.ydb.core.auth.EnvironAuthProvider;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.core.grpc.GrpcTransportBuilder;
import tech.ydb.query.QueryClient;

/** Configures SDK resources only when the application has not supplied them. */
@AutoConfiguration
@ConditionalOnClass(QueryClient.class)
@ConditionalOnProperty(prefix = "ydb", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(YdbProperties.class)
public final class YdbAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(QueryClient.class)
    static class ClientConfiguration {
        @Bean(destroyMethod = "close")
        QueryClient ydbQueryClient(GrpcTransport transport, YdbProperties properties,
                                  ObjectProvider<YdbQueryClientBuilderCustomizer> customizers) {
            var pool = properties.sessionPool();
            pool.validate();
            QueryClient.Builder builder = QueryClient.newClient(transport)
                    .sessionPoolMaxSize(pool.maxSize()).sessionPoolMinSize(pool.minSize()).sessionMaxIdleTime(pool.maxIdleTime());
            customizers.orderedStream().forEach(c -> c.customize(builder));
            return builder.build();
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnMissingBean(GrpcTransport.class)
        static class TransportConfiguration {
            @Bean(destroyMethod = "close")
            GrpcTransport ydbTransport(YdbProperties properties, ObjectProvider<AuthProvider> providers,
                                       ObjectProvider<YdbTransportBuilderCustomizer> customizers, ResourceLoader resources) {
                properties.sessionPool().validate();
                YdbProperties.validateDuration(properties.transport().discoveryTimeout(), "ydb.transport.discovery-timeout");
                URI uri = connection(properties.connectionString());
                GrpcTransportBuilder builder = GrpcTransport.forConnectionString(uri.toString())
                        .withDiscoveryTimeout(properties.transport().discoveryTimeout())
                        .withInitMode(GrpcTransportBuilder.InitMode.valueOf(properties.transport().initMode().name()));
                String certificate = properties.transport().tls().caCertificate();
                if (certificate != null) {
                    if (!"grpcs".equals(uri.getScheme()) || !(certificate.startsWith("classpath:") || certificate.startsWith("file:"))) {
                        throw new IllegalArgumentException("ydb.transport.tls.ca-certificate requires grpcs and a classpath: or file: resource");
                    }
                    try (var stream = resources.getResource(certificate).getInputStream()) { builder.withSecureConnection(stream.readAllBytes()); }
                    catch (IOException ex) { throw new IllegalArgumentException("Cannot read ydb.transport.tls.ca-certificate"); }
                }
                AuthProvider custom = providers.getIfAvailable();
                if (custom != null) builder.withAuthProvider(custom);
                else {
                    var auth = properties.auth();
                    if (auth.mode() == null) throw new IllegalArgumentException("Set ydb.auth.mode or supply an AuthProvider bean");
                    switch (auth.mode()) {
                        case ANONYMOUS -> builder.withAuthProvider(NopAuthProvider.INSTANCE);
                        case TOKEN -> {
                            if (auth.token() == null || auth.token().isBlank()) throw new IllegalArgumentException("ydb.auth.token is required in token mode");
                            builder.withAuthProvider(new TokenAuthProvider(auth.token()));
                        }
                        case ENVIRONMENT -> builder.withAuthProvider(new EnvironAuthProvider());
                    }
                }
                customizers.orderedStream().forEach(c -> c.customize(builder));
                return builder.build();
            }
        }
    }

    private static URI connection(String value) {
        try {
            URI uri = URI.create(value);
            if (!("grpc".equals(uri.getScheme()) || "grpcs".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getPath() == null || uri.getPath().length() < 2 || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("ydb.connection-string must have the form grpc[s]://host[:port]/database without credentials or query parameters");
        }
    }
}
