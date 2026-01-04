package com.ceseats.repository;

import com.ceseats.entity.ApiLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ApiLogRepository extends JpaRepository<ApiLog, Long> {
    
    /**
     * 특정 엔드포인트의 로그 조회
     */
    Page<ApiLog> findByEndpoint(String endpoint, Pageable pageable);
    
    /**
     * 특정 기간의 로그 조회
     */
    Page<ApiLog> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);
    
    /**
     * 에러 로그만 조회
     */
    @Query("SELECT a FROM ApiLog a WHERE a.statusCode >= 400 ORDER BY a.createdAt DESC")
    Page<ApiLog> findErrorLogs(Pageable pageable);
    
    /**
     * 특정 엔드포인트의 통계 조회
     */
    @Query("SELECT COUNT(a) FROM ApiLog a WHERE a.endpoint = :endpoint AND a.createdAt >= :start")
    Long countByEndpointSince(@Param("endpoint") String endpoint, @Param("start") LocalDateTime start);
    
    /**
     * 오래된 로그 삭제 (30일 이상)
     */
    @Query("DELETE FROM ApiLog a WHERE a.createdAt < :cutoffDate")
    void deleteOldLogs(@Param("cutoffDate") LocalDateTime cutoffDate);
}

