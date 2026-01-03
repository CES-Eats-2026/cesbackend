package com.ceseats.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 정규화된 장소 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceResponse {
    private String id; // place_id
    private String name;
    private Integer walkTimeMinutes;
    private String priceLevel; // "$", "$$", "$$$"
    private Double averagePriceEstimate;
    private Boolean openNow;
    private String busyLevel; // "LOW", "MEDIUM", "HIGH", "UNKNOWN"
    private Double rating;
    private Long reviewCount;
    private List<String> photos; // photo_reference 리스트
    private List<ReviewDto> reviews; // 상위 5개 + 최신 5개
    private String oneLineSummary; // editorial_summary
    private String googleMapUrl; // Google Maps direction URL
    private String hookMessage; // 마케팅 Hook 메시지
    private Long viewCount; // 조회수
    private Long viewCountIncrease; // 최근 10분 동안의 조회수 증가량
    private Double latitude;
    private Double longitude;
    private String address;
    private String type; // "restaurant", "cafe", "fastfood", "bar"
    private List<String> types; // Google Places API types 리스트 (Redis에서 가져옴)
    private String website; // 웹사이트 URL (메뉴 정보가 있을 수 있음)

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

