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
public class Store {
    @Id
    @Column(name = "place_id", nullable = false, unique = true)
    private String placeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "address")
    private String address; //주소

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "link")
    private String link;//googleMapsLinks >  placeUri

    @Column(name = "review", columnDefinition = "TEXT")
    @Lob
    private String review; //reviewSummary > text > text

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

}

