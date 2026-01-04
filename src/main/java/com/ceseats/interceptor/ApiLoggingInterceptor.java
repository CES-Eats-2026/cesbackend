package com.ceseats.interceptor;

import com.ceseats.service.ApiLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * API 요청/응답 로깅 Interceptor
 */
@Component
public class ApiLoggingInterceptor implements HandlerInterceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(ApiLoggingInterceptor.class);
    
    @Autowired
    private ApiLogService apiLogService;
    
    private static final String START_TIME_ATTRIBUTE = "startTime";
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 요청 시작 시간 기록
        request.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
        return true;
    }
    
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, 
                          Object handler, ModelAndView modelAndView) {
        // 응답 후 로깅은 afterCompletion에서 처리
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                               Object handler, Exception ex) {
        try {
            // 요청 시작 시간 가져오기
            Long startTime = (Long) request.getAttribute(START_TIME_ATTRIBUTE);
            if (startTime == null) {
                return;
            }
            
            long responseTimeMs = System.currentTimeMillis() - startTime;
            
            // 요청 본문 읽기
            String requestBody = getRequestBody(request);
            
            // 응답 본문 읽기
            String responseBody = getResponseBody(response);
            
            // 비동기로 로그 저장 (성능 영향 최소화)
            apiLogService.logApiRequest(request, response, responseTimeMs, 
                                      requestBody, responseBody, ex);
            
        } catch (Exception e) {
            logger.error("Error in API logging interceptor", e);
        }
    }
    
    /**
     * 요청 본문 읽기
     */
    private String getRequestBody(HttpServletRequest request) {
        try {
            if (request instanceof ContentCachingRequestWrapper) {
                ContentCachingRequestWrapper wrapper = (ContentCachingRequestWrapper) request;
                byte[] content = wrapper.getContentAsByteArray();
                if (content.length > 0) {
                    return new String(content, StandardCharsets.UTF_8);
                }
            } else {
                // ContentCachingRequestWrapper가 아닌 경우
                // 이미 읽은 본문은 다시 읽을 수 없으므로 null 반환
                return null;
            }
        } catch (Exception e) {
            logger.debug("Failed to read request body", e);
        }
        return null;
    }
    
    /**
     * 응답 본문 읽기
     */
    private String getResponseBody(HttpServletResponse response) {
        try {
            if (response instanceof ContentCachingResponseWrapper) {
                ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) response;
                byte[] content = wrapper.getContentAsByteArray();
                if (content.length > 0) {
                    return new String(content, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read response body", e);
        }
        return null;
    }
}

