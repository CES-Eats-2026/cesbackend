package com.ceseats.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 장소 조회수 추적용 Entity
 */
@Entity
@Table(name = "place_views", indexes = {
    @Index(name = "idx_place_id", columnList = "placeId"),
    @Index(name = "idx_updated_at", columnList = "updatedAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaceView {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String placeId; // Google Places place_id

    @Column(nullable = false)
    private Long viewCount = 0L; //조회수

    @Column(nullable = false)
    private Long last10MinViewCount = 0L; //10분 전 조회수 (증가량 계산용)

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = true)
    private LocalDateTime last10MinSnapshotAt; //마지막 스냅샷 저장 시간

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void incrementViewCount() {
        this.viewCount++;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 10분 전 조회수 스냅샷 저장 (증가량 계산용)
     */
    public void update10MinSnapshot() {
        this.last10MinViewCount = this.viewCount;
        this.last10MinSnapshotAt = LocalDateTime.now();
    }

    /**
     * 최근 10분 동안의 조회수 증가량 계산
     */
    public Long get10MinIncrease() {
        return Math.max(0, this.viewCount - this.last10MinViewCount);
    }
}

