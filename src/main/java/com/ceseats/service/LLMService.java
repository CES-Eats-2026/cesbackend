package com.ceseats.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LLMService {

    private static final Logger logger = LoggerFactory.getLogger(LLMService.class);

    @Value("${llm.provider:gemini}")
    private String llmProvider; // "gemini" or "openai"

    @Value("${gemini.api.key:${OPENAI_API_KEY:}}")
    private String geminiApiKey;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent}")
    private String geminiApiUrl;

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String openaiApiUrl;

    @Value("${discord.webhook.llm.url:${discord.webhook.url:}}")
    private String discordWebhookUrl;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Gemini 2.5 Flash 제한: 일일 최대 250회, RPM 10회
    private static final int DAILY_LIMIT = 250;
    private static final int RPM_LIMIT = 10;

    public String generateCesReason(String placeName, String placeType, String reviews, String description) {
        String currentApiKey = "gemini".equalsIgnoreCase(llmProvider) ? geminiApiKey : openaiApiKey;
        if (currentApiKey == null || currentApiKey.isEmpty()) {
            logger.warn("LLM API key is not set for provider {}. Using fallback reason.", llmProvider);
            return generateFallbackReason(placeType);
        }

        try {
            // 리뷰와 설명을 결합
            StringBuilder context = new StringBuilder();
            if (description != null && !description.isEmpty()) {
                context.append("설명: ").append(description).append("\n");
            }
            if (reviews != null && !reviews.isEmpty()) {
                context.append("리뷰 요약: ").append(reviews);
            }

            String prompt = String.format(
                "다음은 CES(Consumer Electronics Show) 전시장 근처의 %s '%s'에 대한 정보입니다.\n\n" +
                "%s\n\n" +
                "위 정보를 바탕으로, CES 참가자들이 이 장소를 선택해야 하는 이유를 한 문장으로 작성해주세요. " +
                "CES 맥락에서 왜 이 장소가 좋은지, 실용적이고 구체적으로 설명해주세요. " +
                "예: '전시장에서 가장 가까운 빠른 식사 장소', 'CES 참가자들이 자주 찾는 네트워킹 장소' 등. " +
                "한국어로 답변해주세요.",
                placeType, placeName, context.toString()
            );

            String response = callLLMInternal(prompt, null, 100, 0.7);
            if (response != null && !response.isEmpty()) {
                return response;
            }
        } catch (Exception e) {
            logger.error("Error calling LLM API for CES reason: {}", e.getMessage(), e);
        }

        return generateFallbackReason(placeType);
    }

    private String generateFallbackReason(String placeType) {
        switch (placeType) {
            case "fastfood":
                return "빠른 식사와 휴식에 완벽한 장소";
            case "cafe":
                return "회의나 작업하기 좋은 분위기";
            case "bar":
                return "CES 후 네트워킹과 휴식에 최적";
            default:
                return "CES 참가자들이 자주 찾는 인기 장소";
        }
    }

    /**
     * Generic LLM call method for RAG recommendations
     */
    public String callLLM(String prompt) {
        return callLLM(prompt, null);
    }

    /**
     * Generic LLM call method for RAG recommendations with user preference tracking
     */
    public String callLLM(String prompt, String userPreference) {
        
        String currentApiKey = "gemini".equalsIgnoreCase(llmProvider) ? geminiApiKey : openaiApiKey;
        if (currentApiKey == null || currentApiKey.isEmpty()) {
            throw new RuntimeException("LLM API key is not set for provider " + llmProvider);
        }

        try {
            String result = callLLMInternal(prompt, userPreference, 1024, 0.3);
            return result;
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 내부 LLM 콜 - llm.provider에 따라 Gemini 또는 OpenAI 호출
     */
    private String callLLMInternal(String prompt, String userPreference, int maxTokens, double temperature) {
        try {
            if ("gemini".equalsIgnoreCase(llmProvider)) {
                log.info("[callLLMInternal] provider=gemini");
                return callGeminiAPI(prompt, userPreference, maxTokens, temperature);
            }
            log.info("[callLLMInternal] provider=openai");
            return callOpenAIAPI(prompt, userPreference, maxTokens, temperature);
        } catch (Exception e) {
            throw new RuntimeException("LLM 콜 실패", e);
        }
    }

    /**
     * Call Gemini API
     */
    private String callGeminiAPI(String prompt, String userPreference, int maxTokens, double temperature) {
        logger.info("[LLMService] callGeminiAPI START - apiUrl: {}, apiKey length: {}, maxTokens: {}, temperature: {}", 
                geminiApiUrl, geminiApiKey != null ? geminiApiKey.length() : 0, maxTokens, temperature);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Gemini API 요청 형식
        Map<String, Object> part = new HashMap<>();
        part.put("text", prompt);
        
        Map<String, Object> content = new HashMap<>();
        content.put("role", "user");
        content.put("parts", new Object[]{part});
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", new Object[]{content});
        
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("maxOutputTokens", maxTokens);
        generationConfig.put("temperature", temperature);
        requestBody.put("generationConfig", generationConfig);

        // API 키를 쿼리 파라미터로 전달
        String urlWithKey = geminiApiUrl + "?key=" + geminiApiKey;
        logger.info("[LLMService] Gemini API URL (without key): {}", geminiApiUrl);
        logger.info("[LLMService] Request body - contents size: 1, maxOutputTokens: {}, temperature: {}", maxTokens, temperature);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        
        try {
            logger.info("[LLMService] Sending request to Gemini API...");
            ResponseEntity<String> response = restTemplate.postForEntity(urlWithKey, request, String.class);
            logger.info("[LLMService] Gemini API response status: {}", response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("[LLMService] Gemini API response body length: {}", response.getBody().length());
                try {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    
                    // API 호출 성공 시 요청 카운터 증가
                    incrementRequestCounters();
                    
                    // 토큰 사용량 로깅 및 Discord 알림
                    logTokenUsageGemini(root, "callLLM", userPreference);
                    
                    // Gemini 응답 형식 파싱
                    JsonNode candidates = root.get("candidates");
                    logger.info("[LLMService] Parsing Gemini response - candidates: {}", candidates != null ? candidates.size() : 0);
                    
                    if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                        JsonNode firstCandidate = candidates.get(0);
                        JsonNode responseContent = firstCandidate.get("content");
                        if (responseContent != null) {
                            JsonNode parts = responseContent.get("parts");
                            if (parts != null && parts.isArray() && parts.size() > 0) {
                                JsonNode textPart = parts.get(0);
                                if (textPart.has("text")) {
                                    String result = textPart.get("text").asText().trim();
                                    logger.info("[LLMService] callGeminiAPI SUCCESS - result length: {}", result.length());
                                    logger.info("[LLMService] LLM response (raw): {}", result);
                                    return result;
                                }
                            }
                        }
                    }
                    logger.warn("[LLMService] Gemini response structure unexpected - candidates: {}", candidates);
                } catch (JsonProcessingException e) {
                    logger.error("[LLMService] Failed to parse Gemini API response: {}", e.getMessage(), e);
                    logger.error("[LLMService] Response body: {}", response.getBody());
                    throw new RuntimeException("Failed to parse Gemini response", e);
                }
            } else {
                logger.error("[LLMService] Gemini API request failed - status: {}, body: {}", 
                        response.getStatusCode(), response.getBody());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String errorBody = e.getResponseBodyAsString();
            logger.error("[LLMService] Gemini API HTTP error - status: {}, response: {}", 
                    e.getStatusCode(), errorBody);
            logger.error("[LLMService] Gemini API URL used: {}", geminiApiUrl);
            logger.error("[LLMService] API key length: {}", geminiApiKey != null ? geminiApiKey.length() : 0);
            throw new RuntimeException("Gemini API call failed: " + e.getStatusCode() + " - " + errorBody, e);
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            String errorBody = e.getResponseBodyAsString();
            logger.error("[LLMService] Gemini API Server error - status: {}, response: {}", 
                    e.getStatusCode(), errorBody);
            throw new RuntimeException("Gemini API server error: " + e.getStatusCode() + " - " + errorBody, e);
        } catch (Exception e) {
            logger.error("[LLMService] callGeminiAPI ERROR", e);
            throw new RuntimeException("Unexpected error calling Gemini API: " + e.getMessage(), e);
        }

        throw new RuntimeException("Failed to get Gemini response");
    }

    /**
     * Call OpenAI API
     */
    private String callOpenAIAPI(String prompt, String userPreference, int maxTokens, double temperature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + geminiApiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        
        requestBody.put("messages", new Object[]{message});
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(openaiApiUrl, request, String.class);

        log.info("[geminiapi 호출]");
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            try {
                log.info("[geminiapi 호출 완료]");
                JsonNode root = objectMapper.readTree(response.getBody());
                
                // 토큰 사용량 로깅 및 Discord 알림
                logTokenUsageOpenAI(root, "callLLM", userPreference);
                
                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode firstChoice = choices.get(0);
                    JsonNode messageNode = firstChoice.get("message");
                    if (messageNode != null) {
                        return messageNode.get("content").asText().trim();
                    }
                }
            } catch (JsonProcessingException e) {
                logger.error("Failed to parse OpenAI API response: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to parse OpenAI response", e);
            }
        }

        throw new RuntimeException("Failed to get OpenAI response");
    }

    /**
     * 토큰 사용량 로깅 헬퍼 메서드 (OpenAI 형식)
     */
    private void logTokenUsageOpenAI(JsonNode root, String methodName, String userPreference) {
        JsonNode usage = root.get("usage");
        if (usage != null) {
            int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
            int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
            int totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0;
            
            // 비용 계산 (gpt-3.5-turbo 기준: input $0.50/1M, output $1.50/1M)
            double cost = (promptTokens * 0.50 / 1_000_000.0) + (completionTokens * 1.50 / 1_000_000.0);
            
            logger.info("[LLM Token Usage - OpenAI] {} - prompt: {}, completion: {}, total: {} (cost: ~${})",
                    methodName, promptTokens, completionTokens, totalTokens, String.format("%.6f", cost));
            
            // 사용자 자연어가 있고 비용이 발생한 경우 Discord로 알림 전송
            if (userPreference != null && !userPreference.trim().isEmpty() && cost > 0) {
                sendDiscordNotification(userPreference, cost);
            }
        }
    }

    /**
     * 토큰 사용량 로깅 헬퍼 메서드 (Gemini 형식)
     */
    private void logTokenUsageGemini(JsonNode root, String methodName, String userPreference) {
        JsonNode usageMetadata = root.get("usageMetadata");
        if (usageMetadata != null) {
            int promptTokens = usageMetadata.has("promptTokenCount") ? usageMetadata.get("promptTokenCount").asInt() : 0;
            int completionTokens = usageMetadata.has("candidatesTokenCount") ? usageMetadata.get("candidatesTokenCount").asInt() : 0;
            int totalTokens = usageMetadata.has("totalTokenCount") ? usageMetadata.get("totalTokenCount").asInt() : 0;
            
            // 비용 계산 (Gemini 2.5 Flash 기준: input $0.30/1M, output $2.50/1M)
            double cost = (promptTokens * 0.30 / 1_000_000.0) + (completionTokens * 2.50 / 1_000_000.0);
            
            logger.info("[LLM Token Usage - Gemini] {} - prompt: {}, completion: {}, total: {} (cost: ~${})",
                    methodName, promptTokens, completionTokens, totalTokens, String.format("%.6f", cost));
            
            // 사용자 자연어가 있고 비용이 발생한 경우 Discord로 알림 전송
            if (userPreference != null && !userPreference.trim().isEmpty() && cost > 0) {
                sendDiscordNotification(userPreference, cost);
            }
        }
    }

    /**
     * API 호출 성공 시 요청 카운터 증가
     */
    private void incrementRequestCounters() {
        if (redisTemplate == null || !"gemini".equalsIgnoreCase(llmProvider)) {
            return;
        }
        
        try {
            // 일일 요청 수 증가
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String dailyKey = "llm:requests:daily:" + today;
            Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
            if (dailyCount == 1) {
                // 첫 요청이면 TTL을 자정까지로 설정 (약 24시간)
                redisTemplate.expire(dailyKey, 25, TimeUnit.HOURS);
            }
            
            // RPM 추적 (최근 1분간 요청 수)
            long currentTime = System.currentTimeMillis();
            String rpmKey = "llm:requests:rpm";
            
            // 현재 요청 추가 (타임스탬프를 멤버로 사용)
            redisTemplate.opsForZSet().add(rpmKey, String.valueOf(currentTime), currentTime);
            
            // 1분 이전 데이터 제거
            long oneMinuteAgo = currentTime - 60000;
            redisTemplate.opsForZSet().removeRangeByScore(rpmKey, 0, oneMinuteAgo);
            
            // RPM 키 TTL 설정 (2분)
            redisTemplate.expire(rpmKey, 2, TimeUnit.MINUTES);
            
        } catch (Exception e) {
            logger.error("Failed to increment request counters: {}", e.getMessage(), e);
        }
    }

    /**
     * Gemini API 요청 제한 정보 조회 (카운터 증가 없이 조회만)
     */
    private Map<String, Object> getRequestLimitInfo() {
        Map<String, Object> info = new HashMap<>();
        
        if (redisTemplate == null || !"gemini".equalsIgnoreCase(llmProvider)) {
            info.put("dailyRemaining", "N/A");
            info.put("rpmCurrent", "N/A");
            info.put("rpmRemaining", "N/A");
            return info;
        }
        
        try {
            // 일일 요청 수 조회
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String dailyKey = "llm:requests:daily:" + today;
            Object dailyCountObj = redisTemplate.opsForValue().get(dailyKey);
            int dailyCount = dailyCountObj != null ? Integer.parseInt(dailyCountObj.toString()) : 0;
            int dailyRemaining = Math.max(0, DAILY_LIMIT - dailyCount);
            info.put("dailyRemaining", dailyRemaining);
            info.put("dailyUsed", dailyCount);
            
            // RPM 추적 (최근 1분간 요청 수)
            long currentTime = System.currentTimeMillis();
            long oneMinuteAgo = currentTime - 60000; // 1분 전
            String rpmKey = "llm:requests:rpm";
            
            // 1분 이전 데이터 제거
            redisTemplate.opsForZSet().removeRangeByScore(rpmKey, 0, oneMinuteAgo);
            
            // 현재 RPM 계산
            Long rpmCount = redisTemplate.opsForZSet().count(rpmKey, oneMinuteAgo, currentTime);
            int rpmCurrent = rpmCount != null ? rpmCount.intValue() : 0;
            int rpmRemaining = Math.max(0, RPM_LIMIT - rpmCurrent);
            
            info.put("rpmCurrent", rpmCurrent);
            info.put("rpmRemaining", rpmRemaining);
            
        } catch (Exception e) {
            logger.error("Failed to get request limit info: {}", e.getMessage(), e);
            info.put("dailyRemaining", "Error");
            info.put("rpmCurrent", "Error");
            info.put("rpmRemaining", "Error");
        }
        
        return info;
    }

    /**
     * Discord 웹훅으로 LLM 호출 알림 전송
     */
    private void sendDiscordNotification(String userPreference, double cost) {
        if (discordWebhookUrl == null || discordWebhookUrl.isEmpty()) {
            logger.debug("Discord webhook URL is not set, skipping notification");
            return;
        }

        try {
            // 사용자 자연어를 100자로 제한
            String truncatedPreference = userPreference.length() > 100 
                ? userPreference.substring(0, 100) + "..." 
                : userPreference;

            // 비용을 한국 원화로 변환 (1 USD = 1,300 KRW)
            double costInKRW = cost * 1300.0;

            // 요청 제한 정보 조회
            Map<String, Object> limitInfo = getRequestLimitInfo();

            // Discord 메시지 포맷
            StringBuilder contentBuilder = new StringBuilder();
            contentBuilder.append("**LLM 호출 발생**\n\n");
            contentBuilder.append("**사용자 자연어:**\n").append(truncatedPreference).append("\n\n");
            contentBuilder.append("**발생 비용:**\n$").append(String.format("%.6f", cost))
                          .append(" (약 ").append(String.format("%.2f", costInKRW)).append("원)\n\n");
            
            // Gemini인 경우 요청 제한 정보 추가
            if ("gemini".equalsIgnoreCase(llmProvider)) {
                contentBuilder.append("**요청 제한 정보:**\n");
                Object dailyRemaining = limitInfo.get("dailyRemaining");
                Object dailyUsed = limitInfo.get("dailyUsed");
                Object rpmCurrent = limitInfo.get("rpmCurrent");
                Object rpmRemaining = limitInfo.get("rpmRemaining");
                
                if (dailyRemaining != null && !dailyRemaining.equals("N/A") && !dailyRemaining.equals("Error")) {
                    contentBuilder.append("일일 남은 요청: ").append(dailyRemaining)
                                  .append(" / ").append(DAILY_LIMIT)
                                  .append(" (사용: ").append(dailyUsed).append(")\n");
                }
                if (rpmCurrent != null && !rpmCurrent.equals("N/A") && !rpmCurrent.equals("Error")) {
                    contentBuilder.append("RPM: ").append(rpmCurrent).append(" / ").append(RPM_LIMIT)
                                  .append(" (남은: ").append(rpmRemaining).append(")");
                }
            }
            
            String content = contentBuilder.toString();

            Map<String, Object> discordPayload = new HashMap<>();
            discordPayload.put("content", content);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(discordPayload, headers);
            restTemplate.postForEntity(discordWebhookUrl, request, String.class);
            
            logger.debug("Discord notification sent successfully");
        } catch (Exception e) {
            logger.error("Failed to send Discord notification: {}", e.getMessage(), e);
        }
    }
}

