package com.ceseats.service;

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
public class FeedbackService {

    @Value("${discord.webhook.url:}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void sendFeedbackToDiscord(String feedback, String imageBase64, String imageName) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            throw new RuntimeException("Discord 웹훅 URL이 설정되지 않았습니다.");
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("content", "**새 피드백이 도착했습니다!**\n\n" + feedback);
            
            Map<String, Object> embed = new HashMap<>();
            embed.put("title", "피드백 내용");
            embed.put("description", feedback);
            embed.put("color", 0x3498db); // 파란색
            embed.put("timestamp", java.time.Instant.now().toString());
            
            payload.put("embeds", new Object[]{embed});

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Discord 웹훅 전송 실패: " + response.getStatusCode() + " " + response.getBody());
            }
        } catch (Exception e) {
            System.err.println("Error sending feedback to Discord: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("피드백 전송에 실패했습니다: " + e.getMessage(), e);
        }
    }
}

