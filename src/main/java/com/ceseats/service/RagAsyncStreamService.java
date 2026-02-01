package com.ceseats.service;

import com.ceseats.config.redis.util.RedisOperator;
import com.ceseats.dto.RagRecommendationRequest;
import com.ceseats.dto.StoreResponse;
import com.ceseats.entity.Store;
import com.ceseats.repository.StoreRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ver.2 Redis Streams 기반 비동기 서비스
 *
 * 1) llm_requests (llm_group): client 요청 수집, LLM worker 처리
 * 2) types 기반 placeId 조회 (Redis type:xxx)
 * 3) db_requests (db_group): DB 조회 요청 저장, DB worker 처리
 * 4) PostgreSQL 조회 후 결과 Redis 저장 (TTL)
 * 5) client polling으로 상태 확인
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAsyncStreamService {
    public static final String STREAM_LLM = "llm_requests";
    public static final String GROUP_LLM = "llm_group";
    public static final String STREAM_DB = "db_requests";
    public static final String GROUP_DB = "db_group";

    private static final Duration RESULT_TTL = Duration.ofMinutes(10);
    private static final double DEFAULT_RADIUS_KM = 5.0;

    private static final String KEY_STATUS = "rag:req:%s:status";
    private static final String KEY_RESULT = "rag:req:%s:result";
    private static final String KEY_ERROR  = "rag:req:%s:error";

    private final RedisOperator redisOperator;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StoreRepository storeRepository;
    private final ReviewService reviewService;
    private final LLMService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 간단 키워드 기반 타입 추출 (LLM 연동 전 임시)
    private static final Set<String> KNOWN_TYPES = new LinkedHashSet<>(Arrays.asList(
            "restaurant", "cafe", "coffee_shop", "asian_restaurant", "breakfast_restaurant", "cafeteria",
            "chinese_restaurant", "fine_dining_restaurant", "italian_restaurant", "mexican_restaurant",
            "thai_restaurant", "wine_bar", "bar", "night_club", "pub", "fast_food_restaurant"
    ));

    public enum Status {
        PROCESSING,
        DONE,
        ERROR
    }

    /** Stream/Redis 직렬화로 requestId가 "\"uuid\"" 형태로 올 수 있어 정규화 */
    public String normalizeRequestId(String requestId) {
        if (requestId == null) return null;
        String t = requestId.trim();
        if (t.isEmpty()) return null;
        return unwrapJsonString(t).trim();
    }

    public String enqueueLlmRequest(RagRecommendationRequest request) {
        final long t0 = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        redisOperator.setStringValue(String.format(KEY_STATUS, requestId), Status.PROCESSING.name(), RESULT_TTL);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("latitude", request.getLatitude());
        payload.put("longitude", request.getLongitude());
        payload.put("maxDistanceKm", request.getMaxDistanceKm());
        payload.put("userPreference", request.getUserPreference());

        try {
            final long tSerializeStart = System.nanoTime();
            String payloadJson = objectMapper.writeValueAsString(payload);
            final long tSerializeMs = msSince(tSerializeStart);
            final long tXaddStart = System.nanoTime();
            redisOperator.addToStream(STREAM_LLM, Map.of(
                    "requestId", requestId,
                    "payload", payloadJson,
                    "createdAt", String.valueOf(System.currentTimeMillis())
            ));
            final long tXaddMs = msSince(tXaddStart);
            log.info("[RAG][{}] enqueueLlmRequest done: serializeMs={}, xaddMs={}, totalMs={}",
                    requestId, tSerializeMs, tXaddMs, msSince(t0));
            return requestId;
        } catch (Exception e) {
            redisOperator.setStringValue(String.format(KEY_STATUS, requestId), Status.ERROR.name(), RESULT_TTL);
            redisOperator.setStringValue(String.format(KEY_ERROR, requestId), "enqueue 실패: " + e.getMessage(), RESULT_TTL);
            log.error("[RAG][{}] enqueueLlmRequest failed: totalMs={}", requestId, msSince(t0), e);
            return requestId;
        }
    }

    public String getStatus(String requestId) {
        String id = requestId != null ? requestId.trim() : null;
        if (id == null || id.isEmpty()) return null;
        String raw = redisOperator.getStringValue(String.format(KEY_STATUS, id));
        return raw != null ? unwrapJsonString(raw) : null;
    }

    public String getResultJson(String requestId) {
        String id = requestId != null ? requestId.trim() : null;
        if (id == null || id.isEmpty()) return null;
        String raw = redisOperator.getStringValue(String.format(KEY_RESULT, id));
        return raw != null ? unwrapJsonString(raw) : null;
    }

    public String getError(String requestId) {
        String id = requestId != null ? requestId.trim() : null;
        if (id == null || id.isEmpty()) return null;
        String raw = redisOperator.getStringValue(String.format(KEY_ERROR, id));
        return raw != null ? unwrapJsonString(raw) : null;
    }

    /** llm_requests 처리: 타입 추출 → Redis에서 placeIds 조회 → db_requests enqueue */
    public void handleLlmMessage(String requestId, String payloadJson) {
        final long t0 = System.nanoTime();
        try {
            final String rid = normalizeRequestId(requestId);
            final long tParseStart = System.nanoTime();
            String normalizedPayloadJson = unwrapJsonString(payloadJson);
            Map<String, Object> payload = objectMapper.readValue(normalizedPayloadJson, new TypeReference<>() {});
            Double lat = asDouble(payload.get("latitude"));
            Double lon = asDouble(payload.get("longitude"));
            Integer maxDistanceKm = asInteger(payload.get("maxDistanceKm"));
            String pref = payload.get("userPreference") != null ? payload.get("userPreference").toString() : null;
            final long parseMs = msSince(tParseStart);

            // 0) 사용자 자연어 100자 제한
            String userPreference = pref;
            if (userPreference != null && userPreference.length() > 100) {
                userPreference = userPreference.substring(0, 100);
            }

            // 1) 실제 LLM 호출로 types 추출 (실패 시 키워드 기반 fallback)
            final long tTypesStart = System.nanoTime();
            List<String> types = extractTypesWithLLM(userPreference);
            final long typesMs = msSince(tTypesStart);

            final long tLookupStart = System.nanoTime();
            Set<String> placeIds = lookupPlaceIdsByTypes(types);
            final long lookupMs = msSince(tLookupStart);

            Map<String, Object> dbPayload = new LinkedHashMap<>();
            dbPayload.put("latitude", lat);
            dbPayload.put("longitude", lon);
            dbPayload.put("maxDistanceKm", maxDistanceKm);
            dbPayload.put("placeIds", new ArrayList<>(placeIds));

            final long tDbSerializeStart = System.nanoTime();
            String dbPayloadJson = objectMapper.writeValueAsString(dbPayload);
            final long dbSerializeMs = msSince(tDbSerializeStart);

            final long tDbXaddStart = System.nanoTime();
            redisOperator.addToStream(STREAM_DB, Map.of(
                    "requestId", rid,
                    "payload", dbPayloadJson,
                    "createdAt", String.valueOf(System.currentTimeMillis())
            ));
            final long dbXaddMs = msSince(tDbXaddStart);

            log.info("[RAG][{}] llm_stage done: parseMs={}, llmTypesMs={}, redisLookupMs={}, dbSerializeMs={}, dbXaddMs={}, placeIds={}, totalMs={}",
                    rid, parseMs, typesMs, lookupMs, dbSerializeMs, dbXaddMs, placeIds.size(), msSince(t0));
        } catch (Exception e) {
            String rid = normalizeRequestId(requestId);
            log.error("[RAG][{}] llm_stage failed: totalMs={}", rid, msSince(t0), e);
            if (rid != null) {
                redisOperator.setStringValue(String.format(KEY_STATUS, rid), Status.ERROR.name(), RESULT_TTL);
                redisOperator.setStringValue(String.format(KEY_ERROR, rid), "LLM 처리 실패: " + e.getMessage(), RESULT_TTL);
            }
        }
    }

    /** db_requests 처리: PostgreSQL 조회 → 결과 Redis 저장(TTL) */
    public void handleDbMessage(String requestId, String payloadJson) {
        final long t0 = System.nanoTime();
        try {
            final String rid = normalizeRequestId(requestId);
            final long tParseStart = System.nanoTime();
            String normalizedPayloadJson = unwrapJsonString(payloadJson);
            Map<String, Object> payload = objectMapper.readValue(normalizedPayloadJson, new TypeReference<>() {});
            Double lat = asDouble(payload.get("latitude"));
            Double lon = asDouble(payload.get("longitude"));
            Integer maxDistanceKm = asInteger(payload.get("maxDistanceKm"));
            List<String> placeIds = asStringList(payload.get("placeIds"));
            final long parseMs = msSince(tParseStart);

            double radiusKm = (maxDistanceKm != null && maxDistanceKm > 0) ? maxDistanceKm.doubleValue() : DEFAULT_RADIUS_KM;

            boolean isRandom = false;
            List<Store> stores;
            final long tQueryStart = System.nanoTime();
            if (placeIds != null && !placeIds.isEmpty()) {
                stores = storeRepository.findStoresWithinRadiusAndPlaceIds(lat, lon, radiusKm, placeIds);
            } else {
                // 타입이 없으면 거리 기준 랜덤(기존 RAG fallback과 동일)
                isRandom = true;
                stores = storeRepository.findRandomStoresWithinRadius(lat, lon, radiusKm);
            }
            final long queryMs = msSince(tQueryStart);

            final long tMapStart = System.nanoTime();
            List<StoreResponse> storeResponses = stores.stream()
                    .map(this::toStoreResponse)
                    .collect(Collectors.toList());
            final long mapMs = msSince(tMapStart);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stores", storeResponses);
            result.put("isRandom", isRandom);

            final long tResultSerializeStart = System.nanoTime();
            String resultJson = objectMapper.writeValueAsString(result);
            final long resultSerializeMs = msSince(tResultSerializeStart);

            final long tRedisWriteStart = System.nanoTime();
            redisOperator.setStringValue(String.format(KEY_RESULT, rid), resultJson, RESULT_TTL);
            redisOperator.setStringValue(String.format(KEY_STATUS, rid), Status.DONE.name(), RESULT_TTL);
            final long redisWriteMs = msSince(tRedisWriteStart);

            log.info("[RAG][{}] db_stage done: parseMs={}, queryMs={}, mapMs={}, resultSerializeMs={}, redisWriteMs={}, stores={}, totalMs={}",
                    rid, parseMs, queryMs, mapMs, resultSerializeMs, redisWriteMs, storeResponses.size(), msSince(t0));
        } catch (Exception e) {
            String rid = normalizeRequestId(requestId);
            log.error("[RAG][{}] db_stage failed: totalMs={}", rid, msSince(t0), e);
            if (rid != null) {
                redisOperator.setStringValue(String.format(KEY_STATUS, rid), Status.ERROR.name(), RESULT_TTL);
                redisOperator.setStringValue(String.format(KEY_ERROR, rid), "DB 처리 실패: " + e.getMessage(), RESULT_TTL);
            }
        }
    }

    private StoreResponse toStoreResponse(Store store) {
        List<String> types = reviewService.getTypes(store.getPlaceId());
        String type = determineType(types);

        StoreResponse res = new StoreResponse();
        res.setId(store.getPlaceId());
        res.setName(store.getName());
        res.setLatitude(store.getLatitude());
        res.setLongitude(store.getLongitude());
        res.setAddress(store.getAddress());
        res.setTypes(types);
        res.setType(type);
        res.setWalkingTime(0);
        res.setEstimatedDuration(30);
        res.setCesReason(store.getReview());
        res.setReviews(Collections.emptyList());
        return res;
    }

    private String determineType(List<String> types) {
        if (types == null || types.isEmpty()) return "other";
        if (types.contains("restaurant")) return "restaurant";
        if (types.contains("cafe") || types.contains("coffee_shop")) return "cafe";
        if (types.contains("fast_food_restaurant")) return "fastfood";
        if (types.contains("bar") || types.contains("night_club")) return "bar";
        return "other";
    }

    private List<String> extractTypesWithLLM(String userPreference) {
        try {
            String prompt = buildPreferenceParsingPrompt(userPreference);
            final long tCallStart = System.nanoTime();
            //String llmResponse = llmService.callLLM(prompt, userPreference);
            String llmResponse = "[\"cafe\", \"coffee_shop\", \"wine_bar\"]";//LLM비용 줄이기 위해 우선 하드코딩
            //LLM 호출 시간 시뮬레이션
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            final long llmCallMs = msSince(tCallStart);
            log.info("[RagAsyncStreamService] LLM response (raw): {}", llmResponse);
            final long tParseStart = System.nanoTime();
            List<String> types = parseTypesFromLLMResponse(llmResponse);
            final long llmParseMs = msSince(tParseStart);
            log.info("[RAG][types] llm_call_ms={}, llm_parse_ms={}, types={}", llmCallMs, llmParseMs, types);
            if (!types.isEmpty()) return types;
        } catch (Exception e) {
            log.warn("[RagAsyncStreamService] LLM 타입 추출 실패, 키워드 기반 fallback 사용: {}", e.getMessage());
        }
        return extractTypesByKeyword(userPreference);
    }

    private String buildPreferenceParsingPrompt(String userPreference) {
        String pref = userPreference != null ? userPreference : "";
        return String.format(
                "사용자의 선호도: \"%s\" 에서 아래 카테고리 목록과 유사/해당하는 값이 있으면 JSON으로 반환해줘.\n" +
                "반환 형식은 둘 중 하나로만:\n" +
                "1) [\"cafe\", \"restaurant\"] 처럼 문자열 배열\n" +
                "2) {\"types\": [\"cafe\", \"restaurant\"]} 형태\n" +
                "가능한 카테고리: %s\n" +
                "매칭이 안되면 [] 또는 {\"types\": []} 로 반환해줘.\n",
                pref,
                KNOWN_TYPES
        );
    }

    private List<String> parseTypesFromLLMResponse(String response) {
        String json = response != null ? response.trim() : "";
        if (json.startsWith("```json")) json = json.substring(7);
        if (json.startsWith("```")) json = json.substring(3);
        if (json.endsWith("```")) json = json.substring(0, json.length() - 3);
        json = json.trim();

        try {
            JsonNode root = objectMapper.readTree(json);

            // ["cafe", "coffee_shop"]
            if (root.isArray()) {
                List<String> types = new ArrayList<>();
                root.forEach(n -> {
                    String s = n.asText().trim();
                    if (!s.isEmpty()) types.add(s);
                });
                return filterKnownTypes(types);
            }

            // {"types":[...]}
            if (root.has("types") && root.get("types").isArray()) {
                List<String> types = new ArrayList<>();
                root.get("types").forEach(n -> types.add(n.asText()));
                return filterKnownTypes(types);
            }

            // {"category": "..."} 또는 {"category":[...]} 방어
            if (root.has("category")) {
                JsonNode categoryNode = root.get("category");
                if (categoryNode.isArray()) {
                    List<String> types = new ArrayList<>();
                    categoryNode.forEach(n -> types.add(n.asText()));
                    return filterKnownTypes(types);
                } else {
                    return filterKnownTypes(Collections.singletonList(categoryNode.asText()));
                }
            }

        } catch (Exception e) {
            log.warn("[RagAsyncStreamService] LLM 응답 JSON 파싱 실패: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<String> filterKnownTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            if (t == null) continue;
            String s = t.trim();
            if (s.isEmpty()) continue;
            if (KNOWN_TYPES.contains(s)) out.add(s);
        }
        return out;
    }

    private List<String> extractTypesByKeyword(String userPreference) {
        if (userPreference == null || userPreference.isBlank()) return Collections.emptyList();
        String lower = userPreference.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String t : KNOWN_TYPES) {
            if (lower.contains(t)) found.add(t);
        }
        return found;
    }

    private Set<String> lookupPlaceIdsByTypes(List<String> types) {
        if (types == null || types.isEmpty()) return Collections.emptySet();
        Set<String> ids = new HashSet<>();
        for (String type : types) {
            String key = "type:" + type;
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) continue;
            if (value instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) ids.add(o.toString());
                }
            } else if (value instanceof String s) {
                try {
                    List<String> parsed = objectMapper.readValue(s, new TypeReference<List<String>>() {});
                    ids.addAll(parsed);
                } catch (Exception ignore) {
                    // ignore
                }
            } else {
                ids.add(value.toString());
            }
        }
        return ids;
    }

    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        return Double.valueOf(o.toString());
    }

    private static Integer asInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        return Integer.valueOf(s);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o == null) return Collections.emptyList();
        if (o instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Stream field 값이 JSON 문자열로 한 번 더 감싸져 들어오는 경우가 있어 이를 복구.
     * 예: "\"{\\\"latitude\\\":36.1,...}\""  ->  "{ \"latitude\": 36.1, ... }"
     */
    private String unwrapJsonString(String maybeJsonOrQuotedJson) {
        if (maybeJsonOrQuotedJson == null) return null;
        String t = maybeJsonOrQuotedJson.trim();
        // 이미 JSON 객체/배열이면 그대로
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            return t;
        }
        // JSON 문자열(따옴표)인 경우 한 겹 벗기기
        if (t.startsWith("\"") && t.endsWith("\"")) {
            try {
                String unwrapped = objectMapper.readValue(t, String.class);
                return unwrapped != null ? unwrapped.trim() : t;
            } catch (Exception ignore) {
                // fallthrough
            }
        }
        return t;
    }
}

