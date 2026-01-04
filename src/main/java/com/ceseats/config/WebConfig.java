package com.ceseats.config;

import com.ceseats.interceptor.ApiLoggingInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 설정 - Interceptor 등록
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Autowired
    private ApiLoggingInterceptor apiLoggingInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiLoggingInterceptor)
                .addPathPatterns("/api/**") // /api로 시작하는 모든 경로
                .excludePathPatterns(
                    "/api/health", // 헬스체크는 제외
                    "/h2-console/**" // H2 콘솔은 제외
                );
    }
}

