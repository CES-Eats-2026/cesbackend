package com.ceseats.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * API 요청 로그 엔티티
 */
@Entity
@Table(name = "api_logs", indexes = {
    @Index(name = "idx_endpoint", columnList = "endpoint"),
    @Index(name = "idx_method", columnList = "method"),
    @Index(name = "idx_created_at", columnList = "createdAt"),
    @Index(name = "idx_status_code", columnList = "statusCode")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 10)
    private String method; // GET, POST, PUT, DELETE 등
    
    @Column(nullable = false, length = 500)
    private String endpoint; // /api/recommendations 등
    
    @Column(length = 50)
    private String ipAddress; // 클라이언트 IP
    
    @Column(length = 500)
    private String userAgent; // User-Agent 헤더
    
    @Column(columnDefinition = "TEXT")
    private String requestBody; // 요청 본문 (JSON)
    
    @Column(columnDefinition = "TEXT")
    private String responseBody; // 응답 본문 (JSON, 일부만 저장)
    
    @Column(nullable = false)
    private Integer statusCode; // HTTP 상태 코드
    
    @Column(nullable = false)
    private Long responseTimeMs; // 응답 시간 (밀리초)
    
    @Column(length = 1000)
    private String errorMessage; // 에러 메시지 (있는 경우)
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

