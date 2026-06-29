package com.smarthome.gateway.config;

import com.smarthome.common.constants.RedisConstants;
import com.smarthome.gateway.messaging.DeviceStatusCacheEvictionListener;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;

import java.time.Duration;

/**
 * =============================================================================
 * Redis Configuration for the Gateway
 * =============================================================================
 * Spring Boot auto-configures the {@link RedisConnectionFactory} and a
 * {@code StringRedisTemplate} from {@code spring.data.redis.*}. Here we add a
 * {@link RedisCacheManager} with per-cache TTLs for the {@code @Cacheable}
 * abstraction (used by the device-status cache introduced in Phase 2).
 *
 * The hot-path features (deduplication, rate limiting, dynamic thresholds) use
 * the {@code StringRedisTemplate} directly rather than the cache abstraction,
 * because they rely on atomic Redis primitives (SET NX, INCR/EXPIRE, HSET).
 * =============================================================================
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /** Cache name for device-status lookups (populated in Phase 2). */
    public static final String DEVICE_STATUS_CACHE = "deviceStatus";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(60))
                .disableCachingNullValues();

        // enableStatistics() lets Spring Boot's CacheMetricsAutoConfiguration
        // export cache.gets{result=hit|miss} for the device-status cache, from
        // which a hit-ratio can be derived in Prometheus/Grafana.
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withCacheConfiguration(DEVICE_STATUS_CACHE,
                        defaults.entryTtl(Duration.ofSeconds(60)))
                .enableStatistics()
                .build();
    }

    /**
     * Subscribes the device-status cache-eviction listener to the Redis channel
     * the Device Registry publishes lifecycle changes on, so cache entries are
     * invalidated the instant a device's status changes.
     */
    @Bean
    public RedisMessageListenerContainer deviceStatusListenerContainer(
            RedisConnectionFactory connectionFactory,
            DeviceStatusCacheEvictionListener evictionListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(evictionListener,
                new ChannelTopic(RedisConstants.DEVICE_STATUS_CHANGED_CHANNEL));
        return container;
    }
}
