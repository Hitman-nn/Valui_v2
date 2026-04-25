package com.valui.user.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Named cache configurations.
     * GenericJackson2JsonRedisSerializer includes @class type info, which allows
     * deserializing Optional<UserEntity> and other wrapper types from Redis.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(SerializationPair.fromSerializer(jsonSerializer))
            .disableCachingNullValues();

        RedisCacheConfiguration usersCacheConfig = defaults
            .entryTtl(Duration.ofMinutes(5));

        RedisCacheConfiguration plansCacheConfig = defaults
            .entryTtl(Duration.ofMinutes(10));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(10)))
            .withCacheConfiguration("users", usersCacheConfig)
            .withCacheConfiguration("plans", plansCacheConfig)
            .build();
    }
}
