package com.ceseats.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        logger.info("[RedisConfig] Creating RedisTemplate...");
        logger.info("[RedisConfig] RedisConnectionFactory: {}", connectionFactory.getClass().getName());
        
        try {
            // Redis 연결 테스트
            connectionFactory.getConnection().ping();
            logger.info("[RedisConfig] ✅ Redis connection test: SUCCESS");
        } catch (Exception e) {
            logger.error("[RedisConfig] ❌ Redis connection test: FAILED - {}", e.getMessage(), e);
        }
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key는 String으로 직렬화
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value는 JSON으로 직렬화 (GenericJackson2JsonRedisSerializer 사용)
        // 이 직렬화기는 객체를 JSON으로 변환하고 @class 필드를 포함합니다
        ObjectMapper objectMapper = new ObjectMapper();
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        // 기본 직렬화 설정
        template.setDefaultSerializer(serializer);
        
        template.afterPropertiesSet();
        logger.info("[RedisConfig] RedisTemplate created successfully");
        return template;
    }
}

