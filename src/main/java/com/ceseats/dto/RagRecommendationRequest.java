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
    /** 최대 거리 (km). 미지정/0 이면 5km 사용 */
    private Integer maxDistanceKm;
    private String userPreference; //자유 텍스트 선호도
}

