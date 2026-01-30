package com.ceseats.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Google Places API v1 searchNearby 응답 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchNearbyResponse {
    private List<Place> places;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Place {
        private String id;
        private DisplayName displayName;
        private Location location;
        private List<String> types;
        @JsonIgnore
        private Integer priceLevel; // 0-4 (레거시, Integer 형식, JSON에서 직접 매핑 안 함)
        @JsonProperty("priceLevel")
        private String priceLevelString; // "PRICE_LEVEL_MODERATE" 형식 (신규 API) 또는 Integer 문자열
        private Double rating;
        private Long userRatingCount;
        private String formattedAddress;
        private String nationalPhoneNumber;
        private String internationalPhoneNumber;
        private String websiteUri;
        private String googleMapsUri;
        private EditorialSummary editorialSummary;
        private GenerativeSummary generativeSummary;
        private List<Review> reviews;
        private RegularOpeningHours regularOpeningHours;
        private CurrentOpeningHours currentOpeningHours;
        
        // PlaceDataRequest와 동일한 구조의 내부 클래스들
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class EditorialSummary {
            private String text;
            private String overview;
            private String languageCode;
        }
        
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Review {
            private String name;
            private String relativePublishTimeDescription;
            private Integer rating;
            private TextContent text;
            private TextContent originalText;
            private String publishTime;
            private Long publishTimeUnix;
            private AuthorAttribution authorAttribution;
            private String author; // 레거시
            
            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class TextContent {
                private String text;
                private String languageCode;
            }
            
            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class AuthorAttribution {
                private String displayName;
                private String uri;
                private String photoUri;
            }
        }
        
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class RegularOpeningHours {
            private Boolean openNow;
            private List<Period> periods;
            private List<String> weekdayDescriptions;
            private String nextOpenTime;
            
            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Period {
                private DayTime open;
                private DayTime close;
                
                @Data
                @NoArgsConstructor
                @AllArgsConstructor
                @JsonIgnoreProperties(ignoreUnknown = true)
                public static class DayTime {
                    private Integer day;
                    private Integer hour;
                    private Integer minute;
                }
            }
        }
        
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class CurrentOpeningHours {
            private Boolean openNow;
            private List<Period> periods;
            private List<String> weekdayDescriptions;
            private String nextOpenTime;
            
            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Period {
                private DayTime open;
                private DayTime close;
                
                @Data
                @NoArgsConstructor
                @AllArgsConstructor
                @JsonIgnoreProperties(ignoreUnknown = true)
                public static class DayTime {
                    private Integer day;
                    private Integer hour;
                    private Integer minute;
                }
            }
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DisplayName {
            private String text;
            private String languageCode;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Location {
            // Google Places API v1 응답: location은 직접 { latitude, longitude } 구조
            private Double latitude;
            private Double longitude;
            
            // 하위 호환성을 위한 latLng 필드도 지원 (중첩 구조)
            private LatLng latLng;

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class LatLng {
                private Double latitude;
                private Double longitude;
            }
            
            // latitude/longitude를 가져오는 헬퍼 메서드
            public Double getLatitude() {
                if (latitude != null) {
                    return latitude;
                }
                if (latLng != null && latLng.getLatitude() != null) {
                    return latLng.getLatitude();
                }
                return null;
            }
            
            public Double getLongitude() {
                if (longitude != null) {
                    return longitude;
                }
                if (latLng != null && latLng.getLongitude() != null) {
                    return latLng.getLongitude();
                }
                return null;
            }
        }
        
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class GenerativeSummary {
            private Overview overview;

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Overview {
                private String text;
            }
        }

    }
}

