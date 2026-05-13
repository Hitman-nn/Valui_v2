package com.valui.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.valui.user.service.GlobalFilterServiceImpl;
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
    public RedisCacheManager cacheManager(RedisConnectionFactory factory, ObjectMapper objectMapper) {
        // Copy Spring Boot's ObjectMapper (JavaTimeModule, etc.) and add @class type info.
        //
        // Must use EVERYTHING (not NON_FINAL) because:
        //   - Java Records are implicitly final → NON_FINAL skips them → cached without @class
        //   - GenericJackson2JsonRedisSerializer always deserializes as Object and requires @class
        //   - The default no-arg GenericJackson2JsonRedisSerializer() also uses EVERYTHING internally
        //
        // LaissezFaireSubTypeValidator allows all types (same as the no-arg constructor default).
        // Spring Boot's objectMapper.getPolymorphicTypeValidator() may be too restrictive.
        ObjectMapper cacheMapper = objectMapper.copy()
            .activateDefaultTypingAsProperty(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.EVERYTHING,
                "@class");
        GenericJackson2JsonRedisSerializer jsonSerializer =
            new GenericJackson2JsonRedisSerializer(cacheMapper);

        // Version prefix: old cache entries (written without @class) are silently skipped on restart.
        // Bump "v3:" → "v4:" etc. whenever the DTO shape or serializer config changes incompatibly.
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
            .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(SerializationPair.fromSerializer(jsonSerializer))
            .prefixCacheNameWith("v4:")
            .disableCachingNullValues();

        RedisCacheConfiguration usersCacheConfig = defaults
            .entryTtl(Duration.ofMinutes(5));

        RedisCacheConfiguration plansCacheConfig = defaults
            .entryTtl(Duration.ofMinutes(10));

        RedisCacheConfiguration globalFiltersCacheConfig = defaults
            .entryTtl(Duration.ofSeconds(30));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaults.entryTtl(Duration.ofMinutes(10)))
            .withCacheConfiguration("users", usersCacheConfig)
            .withCacheConfiguration("plans", plansCacheConfig)
            .withCacheConfiguration(GlobalFilterServiceImpl.FILTERS_CACHE, globalFiltersCacheConfig)
            .build();
    }
}
