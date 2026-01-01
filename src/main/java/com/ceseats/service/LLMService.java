package com.ceseats.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class LLMService {

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateCesReason(String placeName, String placeType, String reviews, String description) {
        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("Warning: OpenAI API key is not set. Using fallback reason.");
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

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o-mini");
            
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            
            requestBody.put("messages", new Object[]{message});
            requestBody.put("max_tokens", 100);
            requestBody.put("temperature", 0.7);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode firstChoice = choices.get(0);
                    JsonNode messageNode = firstChoice.get("message");
                    if (messageNode != null) {
                        String content = messageNode.get("content").asText();
                        return content.trim();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error calling OpenAI API: " + e.getMessage());
            e.printStackTrace();
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
}

