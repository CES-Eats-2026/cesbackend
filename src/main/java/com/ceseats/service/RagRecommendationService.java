package com.ceseats.service;

import com.ceseats.dto.PreferenceFilters;
import com.ceseats.dto.RagRecommendationRequest;
import com.ceseats.dto.StoreResponse;
import com.ceseats.entity.Store;
import com.ceseats.repository.StoreRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG-based restaurant recommendation service
 * Flow: User text → LLM parse → DB/Redis filter → LLM rank → Response
 */
@Slf4j
@Service
public class RagRecommendationService {

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private LLMService llmService;

    @Autowired
    private ReviewService reviewService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * LLM 사용해서 장소 추천 부분
     */
    public RagRecommendationResult getRecommendations(RagRecommendationRequest request) {
        
        try {
            //0. 사용자 자연어를 100자로 제한
            String userPreference = request.getUserPreference();
            if (userPreference != null && userPreference.length() > 100) {
                userPreference = userPreference.substring(0, 100);
            }
            
            //1: 사용자의 선호도를 LLM으로 파싱하기
            PreferenceFilters filters = parsePreference(userPreference);
            log.info("[RagRecommendationService] 사용자의 선호도를 LLM으로 파싱한 필터: {}", filters);

            //2-1. 필터링 (타입) redis에서
            /*
            redis에 아래 형태로 저장됨
            type1 : {id1, id2, id3}
            type2 : {id2, id3}
             */
            Set<String> typeFilteredPlaceIds = new HashSet<>();
            
            //타입별로 Redis에서 place_id 배열(List) 조회
            if (filters.getTypes() != null && !filters.getTypes().isEmpty()) {
                for (String type : filters.getTypes()) {
                    String redisKey = "type:" + type;
                    Object value = redisTemplate.opsForValue().get(redisKey);
                    if (value == null) {
                        continue;
                    }

                    java.util.List<String> placeIdList = new java.util.ArrayList<>();
                    if (value instanceof java.util.List) {
                        @SuppressWarnings("unchecked")
                        java.util.List<Object> rawList = (java.util.List<Object>) value;
                        for (Object o : rawList) {
                            if (o != null) {
                                placeIdList.add(o.toString());
                            }
                        }
                    } else if (value instanceof String) {
                        // 방어적으로 문자열(JSON)일 수도 있는 경우 처리
                        try {
                            java.util.List<String> parsed = objectMapper.readValue(
                                    (String) value,
                                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}
                            );
                            placeIdList.addAll(parsed);
                        } catch (Exception ignore) {
                        }
                    }

                    typeFilteredPlaceIds.addAll(placeIdList);
                }
            }
            
            log.info("[RagRecommendationService] Redis 타입 필터링 결과: {}개 placeIds", typeFilteredPlaceIds.size());

            //2-2: 필터링 (위치, 거리)
            log.info("[RagRecommendationService] Step 2: PostgreSQL에서 필터링");
            List<Store> candidates = filterByPostgreSQL(
                request.getLatitude(),
                request.getLongitude(),
                request.getMaxDistanceKm(),
                typeFilteredPlaceIds
            );
            log.info("[RagRecommendationService] PostgreSQL에서 찾은 후보 : {}", candidates.size());

            //candidates를 StoreResponse로 변환
            List<PlaceContext> contexts = buildPlaceContexts(candidates);
            List<StoreResponse> storeResponses = contexts.stream()
                    .map(this::convertToStoreResponse)
                    .collect(Collectors.toList());

            //결과 반환
            String reason = storeResponses.isEmpty() 
                    ? String.format("조건에 맞는 장소를 찾을 수 없어 랜덤 %d개 장소를 추천합니다.", storeResponses.size())
                    : String.format("%d개의 장소를 추천합니다.", storeResponses.size());
            
            return new RagRecommendationResult(storeResponses, reason);

        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 공개 메서드: 랜덤 장소 반환 (에러 발생 시 사용)
     */
    public RagRecommendationResult getRandomStores(int count, Double latitude, Double longitude) {

        // 반경 내의 모든 장소 가져오기 (50km 반경)
        List<Store> allStores = storeRepository.findStoresWithinRadius(
            latitude,
            longitude,
            50.0
        );

        // 레스토랑/카페 타입 필터링
        List<Store> restaurantCafeStores = allStores.stream()
            .filter(store -> {
                List<String> types = reviewService.getTypes(store.getPlaceId());
                if (types == null || types.isEmpty()) {
                    return false;
                }
                // restaurant 또는 cafe 타입 포함 여부 확인
                return types.stream().anyMatch(type ->
                    type.equals("restaurant") ||
                    type.equals("cafe") ||
                    type.equals("coffee_shop") ||
                    type.contains("restaurant") ||
                    type.contains("cafe")
                );
            })
            .collect(Collectors.toList());

        // 랜덤으로 선택
        Collections.shuffle(restaurantCafeStores);
        List<Store> selected = restaurantCafeStores.stream()
            .limit(count)
            .collect(Collectors.toList());

        // PlaceContext로 변환
        List<PlaceContext> contexts = selected.stream()
            .map(store -> {
                PlaceContext ctx = new PlaceContext();
                ctx.placeId = store.getPlaceId();
                ctx.name = store.getName();
                ctx.latitude = store.getLatitude();
                ctx.longitude = store.getLongitude();
                ctx.address = store.getAddress();
                ctx.types = reviewService.getTypes(store.getPlaceId());
                
                List<Map<String, Object>> reviews = reviewService.getReviews(store.getPlaceId());

                return ctx;
            })
            .collect(Collectors.toList());

        // StoreResponse로 변환
        List<StoreResponse> recommendations = contexts.stream()
            .map(this::convertToStoreResponse)
            .collect(Collectors.toList());

        String reason = String.format("LLM 토큰이 다 사용이 되어, 랜덤 %d개를 반환합니다", recommendations.size());
        return new RagRecommendationResult(recommendations, reason, true);
    }

    /**
     * 타입 필터링 없이 거리만 기준으로 랜덤 장소 반환
     * typeFilteredPlaceIds가 비었을 때 사용하는 로직을 그대로 노출
     */
    public RagRecommendationResult getDistanceOnlyRandomStores(
        Double latitude,
        Double longitude,
        Integer maxDistanceKm
    ) {
        //typeFilteredPlaceIds가 비었으면, 거리만 필터링 (랜덤 3개만 반환)
        List<Store> stores = storeRepository.findRandomStoresWithinRadius(
            latitude,
            longitude,
            maxDistanceKm != null ? maxDistanceKm.doubleValue() : 50.0
        );

        // Store → PlaceContext → StoreResponse 변환
        List<PlaceContext> contexts = buildPlaceContexts(stores);
        List<StoreResponse> recommendations = contexts.stream()
            .map(this::convertToStoreResponse)
            .collect(Collectors.toList());

        String reason = String.format("추천 중 오류가 발생하여, 거리 기준으로 랜덤 %d개를 반환합니다", recommendations.size());
        return new RagRecommendationResult(recommendations, reason, true);
    }

    /**
     * Fallback: 레스토랑/카페 중 랜덤으로 반환 (내부 사용)
     */
    private RagRecommendationResult getFallbackRecommendations(
        Double latitude,
        Double longitude,
        Integer maxDistanceKm
    ) {
        return getRandomStores(3, latitude, longitude);
    }

    /**
     * 1: 사용자 자연어를 LLM 호출로 필터링
     */
    private PreferenceFilters parsePreference(String userPreference) {
        try {
            String prompt = buildPreferenceParsingPrompt(userPreference);
            //사용자 자연어를 LLM 호출 시 전달하여 Discord 알림에 포함
            String llmResponse = llmService.callLLM(prompt, userPreference);
            PreferenceFilters filters = parseLLMResponse(llmResponse);
            return filters;
        } catch (Exception e) {
            throw e;
        }
    }

    private String buildPreferenceParsingPrompt(String userPreference) {
        return String.format(
            "사용자의 선호도:\"%s\"에서 아래 확인 내용 항복과 유사하거나 해당하는 카테고리가 있으면,\n" +
            "json 형식으로 반환해줘. 확인 내용 : [cafe, restaurant*, coffee_shop, asian_restaurant, breakfast_, cafeteria*, chinese_, fine_dining_restaurant *,italian_restaurant, mexican_restaurant, thai_, wine_bar *\n" +
            "도저히 매칭이 안되는 경우엔 random 만을 json에 넣어서 반환해줘."    ,
            userPreference
        );
    }

    private PreferenceFilters parseLLMResponse(String response) {
        try {
            // Extract JSON from response (handle markdown code blocks)
            String json = response.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            }
            if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            return objectMapper.readValue(json, PreferenceFilters.class);
        } catch (Exception e) {
            return new PreferenceFilters(); // Empty filters
        }
    }

    /**
     * 2: PostgreSQL (location, distance, id)에서 필터된 결과 반환
     */
    private List<Store> filterByPostgreSQL(
        Double latitude,
        Double longitude,
        Integer maxDistanceKm,
        Set<String> typeFilteredPlaceIds
    ) {
        //typeFilteredPlaceIds 에 있는 장소id에 대해서 거리 제한 적용
        if (typeFilteredPlaceIds != null && !typeFilteredPlaceIds.isEmpty()) {
            List<Store> stores = storeRepository.findStoresWithinRadiusAndPlaceIds(
                latitude,
                longitude,
                maxDistanceKm != null ? maxDistanceKm.doubleValue() : 50.0,
                new ArrayList<>(typeFilteredPlaceIds)
            );
            return stores;
        } else {
            //typeFilteredPlaceIds가 비었으면, 거리만 필터링 (랜덤 3개만 반환)
            List<Store> stores = storeRepository.findRandomStoresWithinRadius(
                latitude,
                longitude,
                maxDistanceKm != null ? maxDistanceKm.doubleValue() : 50.0
            );
            return stores;
        }
    }

    private Integer parsePriceLevel(String priceLevelStr) {
        if (priceLevelStr == null) return null;
        //Parse "$10 ~ $20" format or "$", "$$", "$$$"
        if (priceLevelStr.contains("$")) {
            int count = (int) priceLevelStr.chars().filter(c -> c == '$').count();
            return Math.min(count, 3);
        }
        return null;
    }


    /**
     * Step 4: Build place contexts for LLM
     */
    private List<PlaceContext> buildPlaceContexts(List<Store> stores) {
        return stores.stream()
            .map(store -> {
                PlaceContext context = new PlaceContext();
                context.placeId = store.getPlaceId();
                context.name = store.getName();
                context.latitude = store.getLatitude();
                context.longitude = store.getLongitude();
                context.address = store.getAddress();

                // Get types from Redis
                context.types = reviewService.getTypes(store.getPlaceId());

                // Get reviews from Redis (for context)
                List<Map<String, Object>> reviews = reviewService.getReviews(store.getPlaceId());

                return context;
            })
            .collect(Collectors.toList());
    }


    private StoreResponse convertToStoreResponse(PlaceContext ctx) {
        StoreResponse response = new StoreResponse();
        response.setId(ctx.placeId);
        response.setName(ctx.name);
        response.setLatitude(ctx.latitude);
        response.setLongitude(ctx.longitude);
        response.setAddress(ctx.address);
        response.setTypes(ctx.types);

        // Set type based on types
        String type = determineType(ctx.types);
        response.setType(type);
        
        // Get reviews
        List<Map<String, Object>> reviews = reviewService.getReviews(ctx.placeId);
        if (reviews != null && !reviews.isEmpty()) {
            List<StoreResponse.ReviewDto> reviewDtos = reviews.stream()
                .limit(5)
                .map(r -> new StoreResponse.ReviewDto(
                    (String) r.get("authorName"),
                    (Integer) r.get("rating"),
                    (String) r.get("text"),
                    r.get("time") != null ? ((Number) r.get("time")).longValue() : null,
                    (String) r.get("relativeTimeDescription")
                ))
                .collect(Collectors.toList());
            response.setReviews(reviewDtos);
        }
        
        response.setWalkingTime(0); // Calculate if needed
        response.setEstimatedDuration(30); // Default
        
        return response;
    }

    private String determineType(List<String> types) {
        if (types == null || types.isEmpty()) return "other";
        
        if (types.contains("restaurant")) return "restaurant";
        if (types.contains("cafe") || types.contains("coffee_shop")) return "cafe";
        if (types.contains("fast_food_restaurant")) return "fastfood";
        if (types.contains("bar") || types.contains("night_club")) return "bar";
        
        return "other";
    }

    /**
     * Internal context class for LLM
     */
    private static class PlaceContext {
        String placeId;
        String name;
        Double latitude;
        Double longitude;
        String address;
        List<String> types;
    }

    /**
     * Recommendation 결과
     */
    public static class RagRecommendationResult {
        private List<StoreResponse> stores;
        private boolean isRandom;

        public RagRecommendationResult(List<StoreResponse> stores, String reason) {
            this(stores, reason, false);
        }

        public RagRecommendationResult(List<StoreResponse> stores, String reason, boolean isRandom) {
            this.stores = stores;
            this.isRandom = isRandom;
        }

        public List<StoreResponse> getStores() {
            return stores;
        }


        public boolean isRandom() {
            return isRandom;
        }
    }
}

