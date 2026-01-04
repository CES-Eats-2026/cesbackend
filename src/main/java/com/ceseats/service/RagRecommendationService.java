package com.ceseats.service;

import com.ceseats.dto.PreferenceFilters;
import com.ceseats.dto.RagRecommendationRequest;
import com.ceseats.dto.StoreResponse;
import com.ceseats.model.Store;
import com.ceseats.repository.StoreRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG-based restaurant recommendation service
 * Flow: User text → LLM parse → DB/Redis filter → LLM rank → Response
 */
@Service
public class RagRecommendationService {

    private static final Logger logger = LoggerFactory.getLogger(RagRecommendationService.class);

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
     * Main recommendation flow
     * Returns recommendation result with reason
     */
    public RagRecommendationResult getRecommendations(RagRecommendationRequest request) {
        logger.info("[RagRecommendationService] getRecommendations START - latitude: {}, longitude: {}, maxDistanceKm: {}, userPreference: {}",
                request.getLatitude(), request.getLongitude(), request.getMaxDistanceKm(), request.getUserPreference());
        
        try {
            // 사용자 자연어를 100자로 제한
            String userPreference = request.getUserPreference();
            if (userPreference != null && userPreference.length() > 100) {
                userPreference = userPreference.substring(0, 100);
                logger.info("[RagRecommendationService] User preference truncated to 100 characters");
            }
            
            // Step 1: Parse user preference using LLM
            logger.info("[RagRecommendationService] Step 1: Parsing user preference using LLM");
            PreferenceFilters filters = parsePreference(userPreference);
            logger.info("[RagRecommendationService] Parsed filters: {}", filters);

            // Step 2: Filter candidates from PostgreSQL (location, distance, price)
            logger.info("[RagRecommendationService] Step 2: Filtering candidates from PostgreSQL");
            List<Store> candidates = filterByPostgreSQL(
                request.getLatitude(),
                request.getLongitude(),
                request.getMaxDistanceKm(),
                filters
            );
            logger.info("[RagRecommendationService] Found {} candidates from PostgreSQL", candidates.size());

            // Step 3: Filter by Redis (types, features)
            logger.info("[RagRecommendationService] Step 3: Filtering by Redis (types, features)");
            List<Store> filtered = filterByRedis(candidates, filters);
            logger.info("[RagRecommendationService] Filtered to {} places after Redis check", filtered.size());

            // Step 4: Build context for LLM
            logger.info("[RagRecommendationService] Step 4: Building context for LLM");
            List<PlaceContext> contexts = buildPlaceContexts(filtered);
            logger.info("[RagRecommendationService] Built {} contexts", contexts.size());

            // Step 5: LLM final ranking and reasoning
            logger.info("[RagRecommendationService] Step 5: LLM final ranking and reasoning");
            RagRecommendationResult result = rankWithLLM(contexts, userPreference);
            logger.info("[RagRecommendationService] LLM ranking completed - stores: {}, reason: {}",
                    result.getStores() != null ? result.getStores().size() : 0, result.getReason());
            
            // Fallback: 결과가 없으면 레스토랑/카페 중 랜덤 반환
            if (result.getStores().isEmpty()) {
                logger.info("[RagRecommendationService] No RAG results found, falling back to random restaurant/cafe");
                result = getFallbackRecommendations(
                    request.getLatitude(),
                    request.getLongitude(),
                    request.getMaxDistanceKm()
                );
                logger.info("[RagRecommendationService] Fallback completed - stores: {}, reason: {}",
                        result.getStores() != null ? result.getStores().size() : 0, result.getReason());
            }
            
            logger.info("[RagRecommendationService] getRecommendations SUCCESS");
            return result;
        } catch (Exception e) {
            logger.error("[RagRecommendationService] getRecommendations ERROR", e);
            throw e;
        }
    }

    /**
     * Fallback: 레스토랑/카페 중 랜덤으로 반환
     */
    private RagRecommendationResult getFallbackRecommendations(
        Double latitude,
        Double longitude,
        Integer maxDistanceKm
    ) {
        // 반경 내의 모든 장소 가져오기
        List<Store> allStores = storeRepository.findStoresWithinRadius(
            latitude,
            longitude,
            maxDistanceKm != null ? maxDistanceKm.doubleValue() : 50.0
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

        // 랜덤으로 최대 3개 선택
        Collections.shuffle(restaurantCafeStores);
        List<Store> selected = restaurantCafeStores.stream()
            .limit(3)
            .collect(Collectors.toList());

        // PlaceContext로 변환
        List<PlaceContext> contexts = selected.stream()
            .map(store -> {
                PlaceContext ctx = new PlaceContext();
                ctx.placeId = store.getPlaceId();
                ctx.name = store.getName();
                ctx.latitude = store.getLatitude();
                ctx.longitude = store.getLongitude();
                ctx.priceLevel = store.getPriceLevel();
                ctx.address = store.getAddress();
                ctx.reason = store.getReason();
                ctx.types = reviewService.getTypes(store.getPlaceId());
                
                List<Map<String, Object>> reviews = reviewService.getReviews(store.getPlaceId());
                if (reviews != null && !reviews.isEmpty()) {
                    ctx.reviewSummary = reviews.stream()
                        .limit(3)
                        .map(r -> String.format("%s: %s", r.get("authorName"), r.get("text")))
                        .collect(Collectors.joining("; "));
                }
                return ctx;
            })
            .collect(Collectors.toList());

        // StoreResponse로 변환
        List<StoreResponse> recommendations = contexts.stream()
            .map(this::convertToStoreResponse)
            .collect(Collectors.toList());

        String fallbackReason = "딱 맞아떨어지는 음식점이 없습니다. 대신, 아래는 어떠신가요?";
        // 추천 이유를 100자로 제한 (토큰 사용량 절감)
        if (fallbackReason.length() > 100) {
            fallbackReason = fallbackReason.substring(0, 100);
        }
        return new RagRecommendationResult(recommendations, fallbackReason);
    }

    /**
     * Step 1: Parse free-text preference into structured filters
     */
    private PreferenceFilters parsePreference(String userPreference) {
        logger.info("[RagRecommendationService] parsePreference START - userPreference: {}", userPreference);
        try {
            String prompt = buildPreferenceParsingPrompt(userPreference);
            logger.info("[RagRecommendationService] Built preference parsing prompt, calling LLM");
            // 사용자 자연어를 LLM 호출 시 전달하여 Discord 알림에 포함
            String llmResponse = llmService.callLLM(prompt, userPreference);
            logger.info("[RagRecommendationService] LLM response received, length: {}", llmResponse != null ? llmResponse.length() : 0);
            PreferenceFilters filters = parseLLMResponse(llmResponse);
            logger.info("[RagRecommendationService] parsePreference SUCCESS - filters: {}", filters);
            return filters;
        } catch (Exception e) {
            logger.error("[RagRecommendationService] parsePreference ERROR", e);
            throw e;
        }
    }

    private String buildPreferenceParsingPrompt(String userPreference) {
        return String.format(
            "Parse the following user preference into structured filters. " +
            "Output ONLY valid JSON, no explanations.\n\n" +
            "User preference: \"%s\"\n\n" +
            "Output format:\n" +
            "{\n" +
            "  \"cuisineTypes\": [\"korean\", \"japanese\"],\n" +
            "  \"placeTypes\": [\"restaurant\", \"cafe\"],\n" +
            "  \"minPriceLevel\": 1,\n" +
            "  \"maxPriceLevel\": 3,\n" +
            "  \"minRating\": 4.0,\n" +
            "  \"keywords\": [\"spicy\", \"vegetarian\"],\n" +
            "  \"mealType\": \"dinner\"\n" +
            "}\n\n" +
            "Rules:\n" +
            "- cuisineTypes: Use Google Places types (korean_restaurant, japanese_restaurant, etc.)\n" +
            "- placeTypes: restaurant, cafe, fast_food_restaurant, bar, etc.\n" +
            "- priceLevel: 1=$, 2=$$, 3=$$$\n" +
            "- If not specified, use null or empty array\n" +
            "Output JSON only:",
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
            logger.error("Failed to parse LLM response: {}", response, e);
            return new PreferenceFilters(); // Empty filters
        }
    }

    /**
     * Step 2: Filter by PostgreSQL (location, distance, price)
     */
    private List<Store> filterByPostgreSQL(
        Double latitude,
        Double longitude,
        Integer maxDistanceKm,
        PreferenceFilters filters
    ) {
        // Use existing repository method for location-based search
        List<Store> stores = storeRepository.findStoresWithinRadius(
            latitude,
            longitude,
            maxDistanceKm != null ? maxDistanceKm.doubleValue() : 50.0
        );

        // Filter by price level if specified
        if (filters.getMinPriceLevel() != null || filters.getMaxPriceLevel() != null) {
            stores = stores.stream()
                .filter(store -> {
                    Integer priceLevel = parsePriceLevel(store.getPriceLevel());
                    if (priceLevel == null) return true;
                    if (filters.getMinPriceLevel() != null && priceLevel < filters.getMinPriceLevel()) {
                        return false;
                    }
                    if (filters.getMaxPriceLevel() != null && priceLevel > filters.getMaxPriceLevel()) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        }

        return stores;
    }

    private Integer parsePriceLevel(String priceLevelStr) {
        if (priceLevelStr == null) return null;
        // Parse "$10 ~ $20" format or "$", "$$", "$$$"
        if (priceLevelStr.contains("$")) {
            int count = (int) priceLevelStr.chars().filter(c -> c == '$').count();
            return Math.min(count, 3);
        }
        return null;
    }

    /**
     * Step 3: Filter by Redis (types, features)
     */
    private List<Store> filterByRedis(List<Store> candidates, PreferenceFilters filters) {
        return candidates.stream()
            .filter(store -> {
                // Get types from Redis
                List<String> types = reviewService.getTypes(store.getPlaceId());
                if (types == null || types.isEmpty()) {
                    return false; // Skip if no types
                }

                // Check place types
                if (filters.getPlaceTypes() != null && !filters.getPlaceTypes().isEmpty()) {
                    boolean matchesPlaceType = types.stream()
                        .anyMatch(type -> filters.getPlaceTypes().contains(type));
                    if (!matchesPlaceType) return false;
                }

                // Check cuisine types
                if (filters.getCuisineTypes() != null && !filters.getCuisineTypes().isEmpty()) {
                    boolean matchesCuisine = types.stream()
                        .anyMatch(type -> filters.getCuisineTypes().contains(type));
                    if (!matchesCuisine) return false;
                }

                return true;
            })
            .collect(Collectors.toList());
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
                context.priceLevel = store.getPriceLevel();
                context.address = store.getAddress();
                context.reason = store.getReason();

                // Get types from Redis
                context.types = reviewService.getTypes(store.getPlaceId());

                // Get reviews from Redis (for context)
                List<Map<String, Object>> reviews = reviewService.getReviews(store.getPlaceId());
                if (reviews != null && !reviews.isEmpty()) {
                    context.reviewSummary = reviews.stream()
                        .limit(3)
                        .map(r -> String.format("%s: %s", r.get("authorName"), r.get("text")))
                        .collect(Collectors.joining("; "));
                }

                return context;
            })
            .collect(Collectors.toList());
    }

    /**
     * Step 5: LLM final ranking and reasoning
     */
    private RagRecommendationResult rankWithLLM(List<PlaceContext> contexts, String userPreference) {
        logger.info("[RagRecommendationService] rankWithLLM START - contexts: {}, userPreference: {}", 
                contexts.size(), userPreference);
        
        if (contexts.isEmpty()) {
            logger.warn("[RagRecommendationService] rankWithLLM - contexts is empty");
            return new RagRecommendationResult(Collections.emptyList(), "조건에 맞는 장소를 찾을 수 없습니다.");
        }

        try {
            // Build context string
            logger.info("[RagRecommendationService] Building context string");
            StringBuilder contextBuilder = new StringBuilder();
            for (int i = 0; i < contexts.size(); i++) {
                PlaceContext ctx = contexts.get(i);
                contextBuilder.append(String.format(
                    "%d. %s\n" +
                    "   Types: %s\n" +
                    "   Price: %s\n" +
                    "   Address: %s\n" +
                    "   Reviews: %s\n\n",
                    i + 1,
                    ctx.name,
                    ctx.types != null ? String.join(", ", ctx.types) : "N/A",
                    ctx.priceLevel != null ? ctx.priceLevel : "N/A",
                    ctx.address != null ? ctx.address : "N/A",
                    ctx.reviewSummary != null ? ctx.reviewSummary : "N/A"
                ));
            }

            String prompt = buildRankingPrompt(userPreference, contextBuilder.toString());
            logger.info("[RagRecommendationService] Built ranking prompt, calling LLM - prompt length: {}", prompt.length());
            // 사용자 자연어를 LLM 호출 시 전달하여 Discord 알림에 포함
            String llmResponse = llmService.callLLM(prompt, userPreference);
            logger.info("[RagRecommendationService] LLM response received, length: {}", llmResponse != null ? llmResponse.length() : 0);

            // Parse ranked place IDs
            logger.info("[RagRecommendationService] Parsing ranking result");
            List<String> rankedIds = parseRankingResult(llmResponse);
            logger.info("[RagRecommendationService] Parsed ranking result - rankedIds: {}", rankedIds.size());

            // Reorder contexts by ranking
            logger.info("[RagRecommendationService] Reordering contexts by ranking");
            Map<String, PlaceContext> contextMap = contexts.stream()
                .collect(Collectors.toMap(ctx -> ctx.placeId, ctx -> ctx));

            List<PlaceContext> ranked = new ArrayList<>();
            for (String id : rankedIds) {
                PlaceContext ctx = contextMap.get(id);
                if (ctx != null) {
                    ranked.add(ctx);
                }
            }
            // Add any not in ranking
            for (PlaceContext ctx : contexts) {
                if (!ranked.contains(ctx)) {
                    ranked.add(ctx);
                }
            }

            // Convert to StoreResponse (limit to top 3)
            logger.info("[RagRecommendationService] Converting to StoreResponse");
            List<StoreResponse> recommendations = ranked.stream()
                .limit(3)
                .map(this::convertToStoreResponse)
                .collect(Collectors.toList());

            // 고정 메시지 생성 (토큰 절감)
            String reason = String.format("%d개의 장소를 추천합니다.", recommendations.size());

            logger.info("[RagRecommendationService] rankWithLLM SUCCESS - recommendations: {}, reason: {}", 
                    recommendations.size(), reason);
            return new RagRecommendationResult(recommendations, reason);
        } catch (Exception e) {
            logger.error("[RagRecommendationService] rankWithLLM ERROR", e);
            throw e;
        }
    }

    private String buildRankingPrompt(String userPreference, String contexts) {
        return String.format(
            "Rank the following restaurants based on user preference. " +
            "Select ONLY the top 3 best matches. " +
            "Output ONLY valid JSON with 'rankedIds' field. No explanations, only JSON.\n\n" +
            "User preference: \"%s\"\n\n" +
            "Restaurants:\n%s\n\n" +
            "REQUIRED OUTPUT FORMAT:\n" +
            "{\n" +
            "  \"rankedIds\": [\"place_id_1\", \"place_id_2\", \"place_id_3\"]\n" +
            "}\n\n" +
            "IMPORTANT:\n" +
            "- Select exactly 3 restaurants from the list above\n" +
            "- Output ONLY the JSON object, no other text\n\n" +
            "Output JSON now:",
            userPreference,
            contexts
        );
    }

    private List<String> parseRankingResult(String response) {
        logger.info("[RagRecommendationService] parseRankingResult START - response length: {}", 
                response != null ? response.length() : 0);
        logger.info("[RagRecommendationService] Raw LLM response: {}", response);
        
        try {
            String json = response.trim();
            logger.info("[RagRecommendationService] After trim: {}", json);
            
            if (json.startsWith("```json")) {
                json = json.substring(7);
                logger.info("[RagRecommendationService] Removed ```json prefix");
            }
            if (json.startsWith("```")) {
                json = json.substring(3);
                logger.info("[RagRecommendationService] Removed ``` prefix");
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
                logger.info("[RagRecommendationService] Removed ``` suffix");
            }
            json = json.trim();
            logger.info("[RagRecommendationService] Final JSON to parse: {}", json);

            JsonNode root = objectMapper.readTree(json);
            List<String> rankedIds = new ArrayList<>();
            if (root.has("rankedIds") && root.get("rankedIds").isArray()) {
                for (JsonNode idNode : root.get("rankedIds")) {
                    rankedIds.add(idNode.asText());
                }
                logger.info("[RagRecommendationService] Parsed rankedIds: {}", rankedIds);
            } else {
                logger.warn("[RagRecommendationService] rankedIds not found or not an array in response");
            }

            logger.info("[RagRecommendationService] parseRankingResult SUCCESS - rankedIds: {}", rankedIds.size());
            return rankedIds;
        } catch (JsonProcessingException e) {
            logger.error("[RagRecommendationService] Failed to parse ranking result JSON: {}", response, e);
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("[RagRecommendationService] Unexpected error parsing ranking result: {}", response, e);
            return Collections.emptyList();
        }
    }

    private StoreResponse convertToStoreResponse(PlaceContext ctx) {
        StoreResponse response = new StoreResponse();
        response.setId(ctx.placeId);
        response.setName(ctx.name);
        response.setLatitude(ctx.latitude);
        response.setLongitude(ctx.longitude);
        response.setAddress(ctx.address);
        response.setTypes(ctx.types);
        
        // Parse price level
        Integer priceLevel = parsePriceLevel(ctx.priceLevel);
        response.setPriceLevel(priceLevel != null ? priceLevel : 2);
        
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
        
        response.setCesReason(ctx.reason != null ? ctx.reason : "추천 장소");
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
        String priceLevel;
        String address;
        String reason;
        List<String> types;
        String reviewSummary;
    }

    /**
     * Recommendation result with reason
     */
    public static class RagRecommendationResult {
        private List<StoreResponse> stores;
        private String reason;

        public RagRecommendationResult(List<StoreResponse> stores, String reason) {
            this.stores = stores;
            this.reason = reason;
        }

        public List<StoreResponse> getStores() {
            return stores;
        }

        public String getReason() {
            return reason;
        }
    }
}

