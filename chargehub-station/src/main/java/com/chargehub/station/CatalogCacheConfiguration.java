package com.chargehub.station;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class CatalogCacheConfiguration {
    @Bean(name = "catalogRedisConnectionFactory")
    @ConditionalOnProperty(name = "chargehub.redis.enabled", havingValue = "true")
    LettuceConnectionFactory catalogRedisConnectionFactory(Environment env) {
        int timeout = bounded(env, "timeout-ms", 150, 25, 2000);
        var standalone = new RedisStandaloneConfiguration(env.getProperty("chargehub.redis.host", "127.0.0.1"),
                bounded(env, "port", 6379, 1, 65535));
        standalone.setDatabase(bounded(env, "database", 0, 0, 15));
        String password = env.getProperty("chargehub.redis.password", "");
        if (!password.isBlank()) standalone.setPassword(RedisPassword.of(password));
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .shutdownTimeout(Duration.ofMillis(timeout))
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofMillis(timeout)).build()).build()).build();
        return new LettuceConnectionFactory(standalone, client);
    }

    @Bean
    CatalogCache catalogCache(@Qualifier("catalogRedisConnectionFactory") ObjectProvider<LettuceConnectionFactory> factories,
                              ObjectProvider<MeterRegistry> metrics, Environment env) {
        var factory = factories.getIfAvailable();
        var template = factory == null ? null : new StringRedisTemplate(factory);
        String prefix = env.getProperty("chargehub.redis.key-prefix", "chargehub:catalog:v1:");
        if (prefix.isBlank() || prefix.length() > 100) throw new IllegalArgumentException("Invalid Redis key prefix");
        return new CatalogCache(template, prefix,
                bounded(env, "ttl-seconds", 30, 1, 120), bounded(env, "jitter-seconds", 10, 0, 30),
                bounded(env, "cooldown-ms", 1000, 100, 30000), metrics.getIfAvailable());
    }

    private static int bounded(Environment env, String name, int fallback, int min, int max) {
        int result = env.getProperty("chargehub.redis." + name, Integer.class, fallback);
        if (result < min || result > max) throw new IllegalArgumentException("Invalid chargehub.redis." + name);
        return result;
    }
}
