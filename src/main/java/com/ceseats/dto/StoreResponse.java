package com.ceseats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoreResponse {
    private String id;
    private String name;
    private String type;
    private Integer walkingTime; // 분
    private Integer estimatedDuration; // 분
    private Integer priceLevel; // 1, 2, 3
    private String cesReason;
    private Double latitude;
    private Double longitude;
    private String address;
    private List<String> photos; // 사진 URL 리스트
    private List<ReviewDto> reviews; // 리뷰 리스트
    private Integer viewCount; // 조회수

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewDto {
        private String authorName;
        private Integer rating;
        private String text;
        private Long time; // Unix timestamp
        private String relativeTimeDescription;
    }
}

