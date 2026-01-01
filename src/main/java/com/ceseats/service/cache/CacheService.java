package com.ceseats.service.cache;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 캐시 서비스 (Redis 스타일 추상화)
 * MVP 레벨 구현, 프로덕션에서는 Redis로 교체 가능
 */
@Service
public class CacheService {

    // Nearby Search 결과 캐시 (10분)
    private final Map<String, CacheEntry<List<String>>> nearbySearchCache = new ConcurrentHashMap<>();
    private static final long NEARBY_SEARCH_TTL_MINUTES = 10;

    // Place Details 캐시 (1시간)
    private final Map<String, CacheEntry<Object>> placeDetailsCache = new ConcurrentHashMap<>();
    private static final long PLACE_DETAILS_TTL_MINUTES = 60;

    /**
     * Nearby Search 결과 캐시 키 생성
     */
    private String generateNearbySearchKey(double latitude, double longitude, int radius) {
        return String.format("nearby:%s:%s:%d", latitude, longitude, radius);
    }

    /**
     * Nearby Search 결과 가져오기
     */
    public List<String> getNearbyPlaces(double latitude, double longitude, int radius) {
        String key = generateNearbySearchKey(latitude, longitude, radius);
        CacheEntry<List<String>> entry = nearbySearchCache.get(key);
        
        if (entry != null && !entry.isExpired(NEARBY_SEARCH_TTL_MINUTES)) {
            return entry.getValue();
        }
        
        // 만료되었거나 없으면 제거
        if (entry != null) {
            nearbySearchCache.remove(key);
        }
        
        return null;
    }

    /**
     * Nearby Search 결과 저장
     */
    public void setNearbyPlaces(double latitude, double longitude, int radius, List<String> placeIds) {
        String key = generateNearbySearchKey(latitude, longitude, radius);
        nearbySearchCache.put(key, new CacheEntry<>(placeIds));
    }

    /**
     * Place Details 가져오기
     */
    @SuppressWarnings("unchecked")
    public <T> T getPlaceDetails(String placeId, Class<T> clazz) {
        CacheEntry<Object> entry = placeDetailsCache.get(placeId);
        
        if (entry != null && !entry.isExpired(PLACE_DETAILS_TTL_MINUTES)) {
            return (T) entry.getValue();
        }
        
        // 만료되었거나 없으면 제거
        if (entry != null) {
            placeDetailsCache.remove(placeId);
        }
        
        return null;
    }

    /**
     * Place Details 저장
     */
    public <T> void setPlaceDetails(String placeId, T details) {
        placeDetailsCache.put(placeId, new CacheEntry<>(details));
    }

    /**
     * 캐시 클리어 (테스트용)
     */
    public void clearAll() {
        nearbySearchCache.clear();
        placeDetailsCache.clear();
    }

    /**
     * 캐시 엔트리 내부 클래스
     */
    private static class CacheEntry<T> {
        private final T value;
        private final LocalDateTime createdAt;

        public CacheEntry(T value) {
            this.value = value;
            this.createdAt = LocalDateTime.now();
        }

        public T getValue() {
            return value;
        }

        public boolean isExpired(long ttlMinutes) {
            return LocalDateTime.now().isAfter(createdAt.plusMinutes(ttlMinutes));
        }
    }
}

