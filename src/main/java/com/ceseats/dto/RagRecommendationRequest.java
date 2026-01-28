package com.ceseats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagRecommendationRequest {
    private Double latitude;
    private Double longitude;
    private Integer maxDistanceKm; //최대 거리 (km)
    private String userPreference; //자유 텍스트 선호도
}

