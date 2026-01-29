package com.ceseats.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "stores")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Store {//google api 호출했을때 json 응답 내에서 값들의 위치
    @Id
    @Column(name = "place_id", nullable = false, unique = true)
    private String placeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Double latitude; //location > latitude

    @Column(nullable = false)
    private Double longitude; //location > longitude

    @Column(name = "address")
    private String address; //formattedAddress

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;//현재시각!

    @Column(name = "link")
    private String link;//googleMapsUri

    @Column(name = "review", columnDefinition = "TEXT")
    @Lob
    private String review; //reviewSummary > text > text

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

}

