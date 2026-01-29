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

@Service
public class ReviewService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewService.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Map<String, Object>> getReviews(String placeId) {
        // 이제 reviews는 Redis에 저장하지 않으므로 항상 null 반환
        // (상위 서비스에서 null/empty 체크 후 동작)
        logger.debug("[ReviewService] getReviews called but reviews are no longer stored in Redis. placeId={}", placeId);
        return null;
    }

    public void setReviews(String placeId, List<Map<String, Object>> reviews) {
        // 리뷰는 더 이상 Redis에 저장하지 않음 (Store 테이블에 저장)
        logger.debug("[ReviewService] setReviews called but reviews are no longer stored in Redis. placeId={}, reviewsSize={}",
                placeId, reviews != null ? reviews.size() : 0);
    }

    public void deleteReviews(String placeId) {
        // 리뷰 키는 더 이상 사용하지 않음
        logger.debug("[ReviewService] deleteReviews called but review keys are no longer used. placeId={}", placeId);
    }

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

            // 1) placeId 기준 types 리스트 저장 (기존 구조 유지)
            String placeTypesKey = "types:" + placeId;
            System.out.println("Setting key: " + placeTypesKey + " with value: " + types);
            redisTemplate.opsForValue().set(placeTypesKey, types);
            System.out.println("Successfully saved " + types.size() + " types to Redis for place: " + placeId + " (key: " + placeTypesKey + ")");

            // 2) type 기준 placeId 배열(List) 저장 (요청 사양: type -> [placeId...])
            //    예: "restaurant" -> ["place1", "place2", ...]
            for (String type : types) {
                if (type == null || type.isEmpty()) {
                    continue;
                }
                String typeKey = "type:" + type;
                // 기존 값 읽기
                Object existing = redisTemplate.opsForValue().get(typeKey);
                java.util.List<String> placeIdList = new java.util.ArrayList<>();
                if (existing instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> rawList = (java.util.List<Object>) existing;
                    for (Object o : rawList) {
                        if (o != null) {
                            placeIdList.add(o.toString());
                        }
                    }
                } else if (existing instanceof String) {
                    // 혹시 문자열(JSON 등)로 저장되어 있는 경우를 방어적으로 처리
                    try {
                        java.util.List<String> parsed = objectMapper.readValue(
                                (String) existing,
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}
                        );
                        placeIdList.addAll(parsed);
                    } catch (Exception ignore) {
                    }
                }

                if (!placeIdList.contains(placeId)) {
                    placeIdList.add(placeId);
                }

                redisTemplate.opsForValue().set(typeKey, placeIdList);
                System.out.println("Updated type key: " + typeKey + " with placeIds: " + placeIdList);
            }

            // 기본적인 존재 여부만 확인 (디버그 용)
            Boolean exists = redisTemplate.hasKey(placeTypesKey);
            System.out.println("Place types key exists check: " + (exists != null && exists ? "YES" : "NO"));
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

