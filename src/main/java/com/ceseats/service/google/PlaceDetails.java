package com.ceseats.service.google;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Places API Place Details 응답 데이터 모델
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaceDetails {
    private String placeId;
    private String name;
    private Double latitude;
    private Double longitude;
    private String address;
    private Boolean openNow;
    private Integer priceLevel; // 0-4 (Google API 기준)
    private Double rating;
    private Long reviewCount;
    private List<String> photoReferences = new ArrayList<>(); // 기존 API용 photo_reference
    private List<PhotoInfo> photos = new ArrayList<>(); // 신규 API용 photo 정보
    private String oneLineSummary;
    private List<Review> reviews = new ArrayList<>();
    private String busyLevel; // "LOW", "MEDIUM", "HIGH", "UNKNOWN" (현재는 UNKNOWN, 추후 확장 가능)
    private List<String> types = new ArrayList<>(); // Google Places API types 필드
    private String website; // 웹사이트 URL (메뉴 정보가 있을 수 있음)

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Review {
        private String authorName;
        private Integer rating;
        private String text;
        private Long time; // Unix timestamp
        private String relativeTimeDescription;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhotoInfo {
        private String name; // 신규 API용: places/PLACE_ID/photos/PHOTO_RESOURCE
        private String photoReference; // 기존 API용: photo_reference
        private Integer widthPx;
        private Integer heightPx;
    }
}

