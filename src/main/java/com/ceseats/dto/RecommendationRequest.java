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
    private String timeOption; //"15", "30", "45", "60", "90"
    private String type; //예시: "all", "restaurant", "cafe", "fastfood", "bar"
}

