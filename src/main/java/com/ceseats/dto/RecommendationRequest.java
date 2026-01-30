package com.ceseats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRequest {
    private Double latitude;
    private Double longitude;
    /** 반경 (미터). 미지정 시 5000(5km) */
    private Integer radiusMeters = 5000;
    private String timeOption; //예시: "15", "30", "45", "60", "90"분
    private String type; //예시: "all", "restaurant", "cafe", "fastfood", "bar"
}

