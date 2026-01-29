package com.ceseats.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Google Places API v1 searchNearby 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchNearbyRequest {
    private List<String> includedTypes;
    private Integer maxResultCount;
    private LocationRestriction locationRestriction;
    private String rankPreference; // "POPULARITY" or "DISTANCE" - 정렬 순서 변경으로 다양한 결과 얻기

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationRestriction {
        private Circle circle;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Circle {
            private Center center;
            private Double radius;

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class Center {
                private Double latitude;
                private Double longitude;
            }
        }
    }
}

