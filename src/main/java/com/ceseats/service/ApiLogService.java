package com.ceseats.service;

import com.ceseats.entity.ApiLog;
import com.ceseats.repository.ApiLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * API 로깅 서비스 (비동기 처리)
 */
@Service
public class ApiLogService {
    
    private static final Logger logger = LoggerFactory.getLogger(ApiLogService.class);
    private static final Logger apiLogger = LoggerFactory.getLogger("API_LOGGER");
    
    @Autowired
    private ApiLogRepository apiLogRepository;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 응답 본문 최대 길이 (너무 긴 응답은 잘라서 저장)
    private static final int MAX_RESPONSE_BODY_LENGTH = 5000;
    
    /**
     * 비동기로 API 로그를 저장
     */
    @Async("apiLogExecutor")
    public void logApiRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            long responseTimeMs,
            String requestBody,
            String responseBody,
            Exception exception) {
        
        try {
            String method = request.getMethod();
            String endpoint = request.getRequestURI();
            String queryString = request.getQueryString();
            if (queryString != null) {
                endpoint += "?" + queryString;
            }
            
            String ipAddress = getClientIpAddress(request);
            String userAgent = request.getHeader("User-Agent");
            int statusCode = response.getStatus();
            
            // 응답 본문이 너무 길면 잘라서 저장
            String truncatedResponseBody = truncateString(responseBody, MAX_RESPONSE_BODY_LENGTH);
            
            // 에러 메시지 추출
            String errorMessage = exception != null ? exception.getMessage() : null;
            if (errorMessage != null && errorMessage.length() > 1000) {
                errorMessage = errorMessage.substring(0, 1000);
            }
            
            // 데이터베이스에 저장
            ApiLog apiLog = ApiLog.builder()
                    .method(method)
                    .endpoint(endpoint)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .requestBody(requestBody)
                    .responseBody(truncatedResponseBody)
                    .statusCode(statusCode)
                    .responseTimeMs(responseTimeMs)
                    .errorMessage(errorMessage)
                    .createdAt(LocalDateTime.now())
                    .build();
            
            apiLogRepository.save(apiLog);
            
            // 구조화된 JSON 로그를 파일로 저장
            logToFile(method, endpoint, ipAddress, statusCode, responseTimeMs, errorMessage);
            
        } catch (Exception e) {
            logger.error("Failed to save API log", e);
        }
    }
    
    /**
     * 구조화된 JSON 로그를 파일로 저장
     */
    private void logToFile(String method, String endpoint, String ipAddress, 
                           int statusCode, long responseTimeMs, String errorMessage) {
        try {
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("timestamp", LocalDateTime.now().toString());
            logEntry.put("method", method);
            logEntry.put("endpoint", endpoint);
            logEntry.put("ipAddress", ipAddress);
            logEntry.put("statusCode", statusCode);
            logEntry.put("responseTimeMs", responseTimeMs);
            if (errorMessage != null) {
                logEntry.put("error", errorMessage);
            }
            
            String jsonLog = objectMapper.writeValueAsString(logEntry);
            apiLogger.info(jsonLog);
            
        } catch (Exception e) {
            logger.error("Failed to write JSON log to file", e);
        }
    }
    
    /**
     * 클라이언트 IP 주소 추출
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For는 여러 IP가 있을 수 있음 (첫 번째가 실제 클라이언트)
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
    
    /**
     * 문자열 자르기
     */
    private String truncateString(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "... (truncated)";
    }
}

