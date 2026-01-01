package com.ceseats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class FeedbackService {

    @Value("${discord.webhook.url:}")
    private String webhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void sendFeedbackToDiscord(String feedback, String imageBase64, String imageName) {
        System.out.println("=== 피드백 전송 시도 ===");
        System.out.println("웹훅 URL 설정 여부: " + (webhookUrl != null && !webhookUrl.isEmpty() ? "설정됨" : "설정되지 않음"));
        
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            String errorMessage = "Discord 웹훅 URL이 설정되지 않았습니다. 환경 변수 DISCORD_WEBHOOK_URL을 설정해주세요.";
            System.err.println("ERROR: " + errorMessage);
            throw new RuntimeException(errorMessage);
        }
        
        System.out.println("웹훅 URL: " + webhookUrl.substring(0, Math.min(50, webhookUrl.length())) + "...");

        // 웹훅 URL 유효성 검사
        if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") && 
            !webhookUrl.startsWith("http://discord.com/api/webhooks/")) {
            System.err.println("Error: Discord 웹훅 URL 형식이 올바르지 않습니다.");
            System.err.println("URL은 https://discord.com/api/webhooks/ 로 시작해야 합니다.");
            throw new RuntimeException("Discord 웹훅 URL 형식이 올바르지 않습니다.");
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            // content는 간단한 헤더만, 실제 내용은 embed에 포함
            payload.put("content", "**새 피드백이 도착했습니다!**");
            
            Map<String, Object> embed = new HashMap<>();
            embed.put("title", "피드백 내용");
            embed.put("description", feedback);
            embed.put("color", 0x3498db); // 파란색
            embed.put("timestamp", java.time.Instant.now().toString());
            
            payload.put("embeds", new Object[]{embed});

            HttpHeaders headers = new HttpHeaders();
            HttpEntity<?> request;
            
            // 이미지가 있는 경우 multipart/form-data로 전송
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                System.out.println("이미지 포함하여 전송합니다. 이미지 이름: " + imageName);
                
                try {
                    // Base64 디코딩
                    byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
                    System.out.println("이미지 크기: " + imageBytes.length + " bytes");
                    
                    // 파일 확장자 추출
                    String fileExtension = "png";
                    if (imageName != null) {
                        int lastDot = imageName.lastIndexOf('.');
                        if (lastDot > 0) {
                            fileExtension = imageName.substring(lastDot + 1).toLowerCase();
                        }
                    }
                    String fileName = imageName != null ? imageName : "feedback_image." + fileExtension;
                    
                    // Multipart 요청 생성
                    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                    
                    // JSON payload를 payload_json 필드로 추가
                    String payloadJson = objectMapper.writeValueAsString(payload);
                    body.add("payload_json", payloadJson);
                    
                    // 이미지 파일을 files[0]로 추가
                    ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                        @Override
                        public String getFilename() {
                            return fileName;
                        }
                    };
                    body.add("files[0]", imageResource);
                    
                    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                    request = new HttpEntity<>(body, headers);
                } catch (Exception e) {
                    System.err.println("이미지 처리 중 오류 발생: " + e.getMessage());
                    e.printStackTrace();
                    // 이미지 처리 실패 시 이미지 없이 전송
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    request = new HttpEntity<>(payload, headers);
                }
            } else {
                // 이미지가 없는 경우 일반 JSON으로 전송
                System.out.println("이미지 없이 전송합니다.");
                headers.setContentType(MediaType.APPLICATION_JSON);
                request = new HttpEntity<>(payload, headers);
            }

            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                String errorMessage = "Discord 웹훅 전송 실패: " + response.getStatusCode();
                if (response.getBody() != null) {
                    errorMessage += " - " + response.getBody();
                }
                System.err.println(errorMessage);
                throw new RuntimeException(errorMessage);
            }
            
            System.out.println("✓ 피드백이 Discord로 성공적으로 전송되었습니다.");
            System.out.println("응답 상태: " + response.getStatusCode());
            System.out.println("응답 본문: " + (response.getBody() != null ? response.getBody() : "없음"));
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String errorMessage = "Discord 웹훅 URL이 유효하지 않거나 만료되었습니다. ";
            if (e.getStatusCode().value() == 404) {
                errorMessage += "웹훅을 찾을 수 없습니다. URL을 확인해주세요.";
            } else if (e.getStatusCode().value() == 401) {
                errorMessage += "웹훅 인증에 실패했습니다. URL을 확인해주세요.";
            } else {
                errorMessage += "HTTP " + e.getStatusCode().value() + ": " + e.getMessage();
            }
            System.err.println("✗ Error sending feedback to Discord: " + errorMessage);
            System.err.println("응답 본문: " + (e.getResponseBodyAsString() != null ? e.getResponseBodyAsString() : "없음"));
            e.printStackTrace();
            throw new RuntimeException(errorMessage, e);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            String errorMessage = "Discord 웹훅 서버에 연결할 수 없습니다. 네트워크를 확인해주세요.";
            System.err.println("✗ Error sending feedback to Discord: " + errorMessage);
            System.err.println("원인: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(errorMessage, e);
        } catch (Exception e) {
            String errorMessage = "피드백 전송에 실패했습니다: " + e.getMessage();
            System.err.println("✗ Error sending feedback to Discord: " + errorMessage);
            System.err.println("예외 타입: " + e.getClass().getName());
            e.printStackTrace();
            throw new RuntimeException(errorMessage, e);
        }
    }
}

