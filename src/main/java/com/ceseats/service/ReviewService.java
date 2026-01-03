package com.ceseats.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Redis에서 place_id를 key로 reviews를 조회하는 서비스
 */
@Service
public class ReviewService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewService.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * place_id로 reviews 조회
     * @param placeId Google Places place_id
     * @return reviews 리스트 (JSON 문자열 또는 객체)
     */
    public List<Map<String, Object>> getReviews(String placeId) {
        try {
            Object reviews = redisTemplate.opsForValue().get(placeId);
            if (reviews == null) {
                return null;
            }

            // Redis에서 가져온 데이터가 문자열인 경우 파싱
            if (reviews instanceof String) {
                return objectMapper.readValue((String) reviews, new TypeReference<List<Map<String, Object>>>() {});
            } else if (reviews instanceof List) {
                // 이미 List인 경우 그대로 반환
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> reviewsList = (List<Map<String, Object>>) reviews;
                return reviewsList;
            }

            return null;
        } catch (Exception e) {
            // 로깅은 필요시 추가
            return null;
        }
    }

    /**
     * place_id로 reviews 저장
     * @param placeId Google Places place_id
     * @param reviews reviews 리스트
     */
    public void setReviews(String placeId, List<Map<String, Object>> reviews) {
        try {
            System.out.println("=== setReviews called ===");
            System.out.println("placeId: " + placeId);
            System.out.println("reviews: " + (reviews != null ? reviews.size() + " items" : "null"));
            
            if (placeId == null || placeId.isEmpty()) {
                System.err.println("WARNING: Cannot save reviews - placeId is null or empty");
                return;
            }
            if (reviews == null || reviews.isEmpty()) {
                System.out.println("No reviews to save for place: " + placeId);
                return;
            }
            
            // RedisTemplate이 null인지 확인
            if (redisTemplate == null) {
                System.err.println("ERROR: redisTemplate is null!");
                return;
            }
            
            // Redis 연결 정보 확인
            try {
                String host = redisConnectionFactory.getConnection().getNativeConnection().toString();
                System.out.println("Redis connection: " + host);
            } catch (Exception ex) {
                System.err.println("Could not get Redis connection info: " + ex.getMessage());
            }
            
            // GenericJackson2JsonRedisSerializer를 사용하므로 List를 직접 저장
            // opsForValue().set()은 기본적으로 동기적으로 동작합니다
            redisTemplate.opsForValue().set(placeId, reviews);
            System.out.println("Successfully saved " + reviews.size() + " reviews to Redis for place: " + placeId);
            
            // 실제 Redis 연결 정보 확인
            try {
                org.springframework.data.redis.connection.RedisConnection connection = redisConnectionFactory.getConnection();
                System.out.println("Redis connection class: " + connection.getClass().getName());
                System.out.println("Redis connection native: " + connection.getNativeConnection().getClass().getName());
                
                // Lettuce 연결 정보 확인
                if (connection.getNativeConnection() instanceof io.lettuce.core.api.sync.RedisCommands) {
                    io.lettuce.core.api.sync.RedisCommands<?, ?> syncCommands = (io.lettuce.core.api.sync.RedisCommands<?, ?>) connection.getNativeConnection();
                    System.out.println("Redis sync commands available");
                    
                    // 실제 연결된 호스트 확인
                    if (syncCommands instanceof io.lettuce.core.RedisClient) {
                        System.out.println("Redis client info available");
                    }
                }
                
                // 연결 상태 확인
                String pingResult = connection.ping();
                System.out.println("Redis PING result: " + pingResult);
            } catch (Exception e) {
                System.err.println("Error getting Redis connection info: " + e.getMessage());
                e.printStackTrace();
            }
            
            // 즉시 확인 (다른 연결로 조회)
            Boolean exists = redisTemplate.hasKey(placeId);
            System.out.println("Key exists check: " + (exists != null && exists ? "YES" : "NO"));
            
            // 실제 Redis에 저장된 키 확인 (직렬화된 키로)
            try {
                @SuppressWarnings("unchecked")
                org.springframework.data.redis.serializer.RedisSerializer<String> keySerializer = 
                    (org.springframework.data.redis.serializer.RedisSerializer<String>) redisTemplate.getKeySerializer();
                byte[] keyBytes = keySerializer.serialize(placeId);
                if (keyBytes != null) {
                    String keyString = new String(keyBytes);
                    System.out.println("Serialized key: " + keyString);
                }
            } catch (Exception e) {
                System.err.println("Error serializing key: " + e.getMessage());
            }
            
            // 저장 확인
            Object saved = redisTemplate.opsForValue().get(placeId);
            System.out.println("Verification: Retrieved from Redis - " + (saved != null ? "exists" : "null"));
        } catch (Exception e) {
            System.err.println("Error saving reviews to Redis for place: " + placeId + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * place_id로 reviews 삭제
     * @param placeId Google Places place_id
     */
    public void deleteReviews(String placeId) {
        redisTemplate.delete(placeId);
    }

    /**
     * place_id로 types 조회
     * @param placeId Google Places place_id
     * @return types 리스트
     */
    public List<String> getTypes(String placeId) {
        try {
            // Redis 연결 확인
            if (redisTemplate == null) {
                logger.error("[Redis] getTypes - redisTemplate is NULL!");
                return null;
            }
            
            String key = "types:" + placeId;
            logger.info("[Redis] getTypes - placeId: {}, key: {}", placeId, key);
            
            // Redis 연결 테스트
            try {
                redisTemplate.getConnectionFactory().getConnection().ping();
                logger.info("[Redis] getTypes - Redis connection OK");
            } catch (Exception e) {
                logger.error("[Redis] getTypes - Redis connection FAILED: {}", e.getMessage(), e);
                return null;
            }
            
            Object types = redisTemplate.opsForValue().get(key);
            if (types == null) {
                logger.warn("[Redis] getTypes - types is null for placeId: {} (key not found in Redis)", placeId);
                return null;
            }

            // Redis에서 가져온 데이터가 문자열인 경우 파싱
            if (types instanceof String) {
                List<String> parsedTypes = objectMapper.readValue((String) types, new TypeReference<List<String>>() {});
                logger.info("[Redis] getTypes - parsed from String, placeId: {}, types: {}", placeId, parsedTypes);
                return parsedTypes;
            } else if (types instanceof List) {
                // 이미 List인 경우 그대로 반환
                @SuppressWarnings("unchecked")
                List<String> typesList = (List<String>) types;
                logger.info("[Redis] getTypes - found List, placeId: {}, types: {}", placeId, typesList);
                return typesList;
            }

            logger.warn("[Redis] getTypes - unknown type: {}", types != null ? types.getClass().getName() : "null");
            return null;
        } catch (Exception e) {
            logger.error("[Redis] getTypes - ERROR for placeId: {}", placeId, e);
            return null;
        }
    }

    /**
     * place_id로 types 저장
     * @param placeId Google Places place_id
     * @param types types 리스트
     */
    public void setTypes(String placeId, List<String> types) {
        System.out.println("=== setTypes called ===");
        System.out.println("placeId: " + placeId);
        System.out.println("types: " + (types != null ? types.size() + " items" : "null"));
        if (types != null && !types.isEmpty()) {
            System.out.println("types content: " + types);
        }
        
        try {
            if (placeId == null || placeId.isEmpty()) {
                System.err.println("WARNING: Cannot save types - placeId is null or empty");
                return;
            }
            if (types == null || types.isEmpty()) {
                System.out.println("No types to save for place: " + placeId);
                return;
            }
            
            // RedisTemplate이 null인지 확인
            if (redisTemplate == null) {
                System.err.println("ERROR: redisTemplate is null!");
                return;
            }
            
            System.out.println("redisTemplate is not null, proceeding to save...");
            
            String key = "types:" + placeId;
            System.out.println("Setting key: " + key + " with value: " + types);
            
            // Redis 연결 정보 확인
            try {
                String host = redisConnectionFactory.getConnection().getNativeConnection().toString();
                System.out.println("Redis connection: " + host);
            } catch (Exception ex) {
                System.err.println("Could not get Redis connection info: " + ex.getMessage());
            }
            
            // GenericJackson2JsonRedisSerializer를 사용하므로 List를 직접 저장
            // opsForValue().set()은 기본적으로 동기적으로 동작합니다
            redisTemplate.opsForValue().set(key, types);
            System.out.println("Successfully saved " + types.size() + " types to Redis for place: " + placeId + " (key: " + key + ")");
            
            // 즉시 확인 (다른 연결로 조회)
            Boolean exists = redisTemplate.hasKey(key);
            System.out.println("Key exists check: " + (exists != null && exists ? "YES" : "NO"));
            
            // 실제 Redis에 저장된 키 확인 (직렬화된 키로)
            try {
                @SuppressWarnings("unchecked")
                org.springframework.data.redis.serializer.RedisSerializer<String> keySerializer = 
                    (org.springframework.data.redis.serializer.RedisSerializer<String>) redisTemplate.getKeySerializer();
                byte[] keyBytes = keySerializer.serialize(key);
                if (keyBytes != null) {
                    String keyString = new String(keyBytes);
                    System.out.println("Serialized key: " + keyString);
                }
            } catch (Exception e) {
                System.err.println("Error serializing key: " + e.getMessage());
            }
            
            // 저장 확인 (즉시 조회)
            Object saved = redisTemplate.opsForValue().get(key);
            System.out.println("Verification: Retrieved from Redis - " + (saved != null ? "exists (type: " + saved.getClass().getSimpleName() + ")" : "null"));
            if (saved != null) {
                System.out.println("Retrieved value: " + saved);
            } else {
                System.err.println("WARNING: Key was saved but retrieval returned null! Key: " + key);
            }
        } catch (Exception e) {
            System.err.println("ERROR: Exception in setTypes for place: " + placeId + " - " + e.getMessage());
            System.err.println("Exception class: " + e.getClass().getName());
            e.printStackTrace();
        }
    }

    /**
     * place_id로 types 삭제
     * @param placeId Google Places place_id
     */
    public void deleteTypes(String placeId) {
        String key = "types:" + placeId;
        redisTemplate.delete(key);
    }
}

