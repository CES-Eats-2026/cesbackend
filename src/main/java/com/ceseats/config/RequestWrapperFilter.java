package com.ceseats.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * 요청/응답 본문을 캐싱하기 위한 필터
 * Interceptor에서 요청/응답 본문을 읽을 수 있도록 함
 */
@Component
public class RequestWrapperFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) 
            throws ServletException, IOException {
        
        // 요청 본문을 캐싱할 수 있도록 래핑
        ContentCachingRequestWrapper requestWrapper = 
            new ContentCachingRequestWrapper(request);
        
        // 응답 본문을 캐싱할 수 있도록 래핑
        ContentCachingResponseWrapper responseWrapper = 
            new ContentCachingResponseWrapper(response);
        
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            // 응답 본문을 클라이언트로 복사 (래핑으로 인해 본문이 비어있을 수 있음)
            responseWrapper.copyBodyToResponse();
        }
    }
}

