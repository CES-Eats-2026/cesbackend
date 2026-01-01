package com.ceseats.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 장소 검색 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaceSearchRequest {
    private Double latitude;
    private Double longitude;
    private Integer radius = 100; // 기본값 100m
    private String sortBy; // "price_asc", "view_desc"
}

