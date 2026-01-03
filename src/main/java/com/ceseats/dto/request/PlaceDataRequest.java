package com.ceseats.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Google Places API v1에서 받은 장소 데이터 DTO
 * JSON에서 추출하여 PostgreSQL stores 테이블과 Redis에 저장
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaceDataRequest {
    private String name; // "places/ChIJiyxvtBdawokRHeEVToh8Te4" 형식
    private String id; // "ChIJiyxvtBdawokRHeEVToh8Te4" (place_id)
    private List<String> types; // ["bar", "event_venue", "restaurant", ...]
    private String nationalPhoneNumber;
    private String internationalPhoneNumber;
    private String formattedAddress;
    private String shortFormattedAddress; // 짧은 형식의 주소
    private List<AddressComponent> addressComponents;
    private PlusCode plusCode;
    private Location location;
    private Viewport viewport;
    private DisplayName displayName; // displayName.text 또는 displayName 객체
    private String displayNameText; // displayName이 단순 String인 경우를 위한 fallback
    private String primaryType; // "bar", "restaurant" 등
    private Integer priceLevel; // 0-4 (Google API 기준, 레거시)
    private String priceLevelString; // "PRICE_LEVEL_EXPENSIVE" 형식 (신규)
    private Double rating;
    private Long userRatingsTotal;
    private Long userRatingCount; // userRatingCount 우선 사용
    private Integer utcOffsetMinutes; // UTC 오프셋 (분)
    private String adrFormatAddress; // HTML 형식의 주소
    private String businessStatus; // "OPERATIONAL", "CLOSED_PERMANENTLY" 등
    private String iconMaskBaseUri; // 아이콘 URI
    private String iconBackgroundColor; // 아이콘 배경색
    private PrimaryTypeDisplayName primaryTypeDisplayName; // 주요 타입 표시명
    private Boolean takeout; // 테이크아웃 가능 여부
    private Boolean delivery; // 배달 가능 여부
    private Boolean dineIn; // 매장 식사 가능 여부
    private Boolean reservable; // 예약 가능 여부
    private Boolean servesBeer; // 맥주 제공 여부
    private Boolean servesWine; // 와인 제공 여부
    private Boolean servesVegetarianFood; // 채식 메뉴 제공 여부
    private Boolean liveMusic; // 라이브 음악 제공 여부
    private Boolean servesCocktails; // 칵테일 제공 여부
    private Boolean servesCoffee; // 커피 제공 여부
    private Boolean goodForChildren; // 아이들에게 적합한지 여부
    private Boolean restroom; // 화장실 유무
    private Boolean goodForGroups; // 그룹에 적합한지 여부
    private Boolean goodForWatchingSports; // 스포츠 관람에 적합한지 여부
    private PaymentOptions paymentOptions; // 결제 옵션
    private ParkingOptions parkingOptions; // 주차 옵션
    private AccessibilityOptions accessibilityOptions; // 접근성 옵션
    private Boolean openNow; // currentOpeningHours.openNow 또는 openingHours.openNow
    private CurrentOpeningHours currentOpeningHours;
    private List<CurrentSecondaryOpeningHours> currentSecondaryOpeningHours; // 보조 영업 시간
    private OpeningHours openingHours;
    private RegularOpeningHours regularOpeningHours; // regularOpeningHours.openNow 우선 사용
    private List<RegularSecondaryOpeningHours> regularSecondaryOpeningHours; // 정규 보조 영업 시간
    private List<Review> reviews;
    private EditorialSummary editorialSummary;
    private GenerativeSummary generativeSummary; // AI 생성 요약
    private AddressDescriptor addressDescriptor; // 주소 설명자 (랜드마크, 지역)
    private GoogleMapsLinks googleMapsLinks; // Google Maps 링크들
    private PriceRange priceRange; // 가격 범위
    private ReviewSummary reviewSummary; // 리뷰 요약
    private TimeZone timeZone; // 타임존
    private PostalAddress postalAddress; // 우편 주소
    private String website;
    private String websiteUri; // websiteUri 우선 사용
    private String googleMapsUri; // Google Maps URI (레거시)
    private List<Photo> photos;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AddressComponent {
        private String longText;
        private String shortText;
        private List<String> types;
        private String languageCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlusCode {
        private String globalCode;
        private String compoundCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {
        private Double latitude;
        private Double longitude;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Viewport {
        private LowHigh low;
        private LowHigh high;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class LowHigh {
            private Double latitude;
            private Double longitude;
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
    public static class PrimaryTypeDisplayName {
        private String text;
        private String languageCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentOpeningHours {
        private Boolean openNow;
        private List<Period> periods;
        private List<String> weekdayDescriptions;
        private String nextOpenTime; // ISO 8601 형식

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
                private Integer day; // 0=Sunday, 1=Monday, ..., 6=Saturday
                private Integer hour; // 0-23
                private Integer minute; // 0-59
                private Boolean truncated; // 시간이 잘렸는지 여부
                private DateInfo date; // 날짜 정보

                @Data
                @NoArgsConstructor
                @AllArgsConstructor
                @JsonIgnoreProperties(ignoreUnknown = true)
                public static class DateInfo {
                    private Integer year;
                    private Integer month; // 1-12
                    private Integer day; // 1-31
                }
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentSecondaryOpeningHours {
        private Boolean openNow;
        private List<Period> periods;
        private List<String> weekdayDescriptions;
        private String secondaryHoursType; // "HAPPY_HOUR", "DELIVERY", "TAKEOUT" 등
        private String nextOpenTime; // ISO 8601 형식

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
                private Integer day; // 0=Sunday, 1=Monday, ..., 6=Saturday
                private Integer hour; // 0-23
                private Integer minute; // 0-59
                private Boolean truncated; // 시간이 잘렸는지 여부
                private DateInfo date; // 날짜 정보 (currentSecondaryOpeningHours에만 있음)

                @Data
                @NoArgsConstructor
                @AllArgsConstructor
                @JsonIgnoreProperties(ignoreUnknown = true)
                public static class DateInfo {
                    private Integer year;
                    private Integer month; // 1-12
                    private Integer day; // 1-31
                }
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RegularSecondaryOpeningHours {
        private Boolean openNow;
        private List<Period> periods;
        private List<String> weekdayDescriptions;
        private String secondaryHoursType; // "HAPPY_HOUR", "DELIVERY", "TAKEOUT" 등
        private String nextOpenTime; // ISO 8601 형식

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
                private Integer day; // 0=Sunday, 1=Monday, ..., 6=Saturday
                private Integer hour; // 0-23
                private Integer minute; // 0-59
                // regularSecondaryOpeningHours에는 date 필드가 없음
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpeningHours {
        private Boolean openNow;
        private List<String> weekdayDescriptions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RegularOpeningHours {
        private Boolean openNow;
        private List<Period> periods;
        private List<String> weekdayDescriptions;
        private String nextOpenTime; // ISO 8601 형식

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
                private Integer day; // 0=Sunday, 1=Monday, ..., 6=Saturday
                private Integer hour; // 0-23
                private Integer minute; // 0-59
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Review {
        private String name; // "places/.../reviews/..." 형식
        private String relativePublishTimeDescription; // "2 months ago" 형식
        private Integer rating;
        private TextContent text; // { text: String, languageCode: String }
        private TextContent originalText; // { text: String, languageCode: String }
        private AuthorAttribution authorAttribution; // { displayName, uri, photoUri }
        private String publishTime; // ISO 8601 형식
        private Long publishTimeUnix; // Unix timestamp (레거시)
        private String flagContentUri; // 신고 URI
        private String googleMapsUri; // Google Maps 리뷰 URI
        private String author; // 레거시 필드
        private String authorAttributionString; // 레거시 필드

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
    public static class EditorialSummary {
        private String text; // text 필드 (신규)
        private String overview; // overview 필드 (레거시)
        private String languageCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Photo {
        private String name; // "places/ChIJiyxvtBdawokRHeEVToh8Te4/photos/..."
        private Integer widthPx;
        private Integer heightPx;
        private List<AuthorAttribution> authorAttributions; // { displayName, uri, photoUri } 배열
        private String flagContentUri; // 신고 URI
        private String googleMapsUri; // Google Maps 사진 URI

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
    public static class PaymentOptions {
        private Boolean acceptsCreditCards; // 신용카드 수락 여부
        private Boolean acceptsDebitCards; // 체크카드 수락 여부
        private Boolean acceptsCashOnly; // 현금만 수락 여부
        private Boolean acceptsNfc; // NFC 결제 수락 여부
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParkingOptions {
        private Boolean freeParkingLot; // 무료 주차장 여부
        private Boolean valetParking; // 발렛 파킹 여부
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccessibilityOptions {
        private Boolean wheelchairAccessibleParking; // 휠체어 접근 가능 주차장 여부
        private Boolean wheelchairAccessibleEntrance; // 휠체어 접근 가능 입구 여부
        private Boolean wheelchairAccessibleRestroom; // 휠체어 접근 가능 화장실 여부
        private Boolean wheelchairAccessibleSeating; // 휠체어 접근 가능 좌석 여부
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenerativeSummary {
        private TextContent overview; // { text, languageCode }
        private String overviewFlagContentUri; // 신고 URI
        private TextContent disclosureText; // { text, languageCode }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class TextContent {
            private String text;
            private String languageCode;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AddressDescriptor {
        private List<Landmark> landmarks; // 랜드마크 리스트
        private List<Area> areas; // 지역 리스트

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Landmark {
            private String name; // "places/..." 형식
            private String placeId;
            private DisplayName displayName; // { text, languageCode }
            private List<String> types;
            private Double straightLineDistanceMeters; // 직선 거리 (미터)
            private Double travelDistanceMeters; // 이동 거리 (미터)
            private String spatialRelationship; // "AROUND_THE_CORNER", "DOWN_THE_ROAD" 등
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Area {
            private String name; // "places/..." 형식
            private String placeId;
            private DisplayName displayName; // { text, languageCode }
            private String containment; // "WITHIN" 등
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoogleMapsLinks {
        private String directionsUri; // 길찾기 URI
        private String placeUri; // 장소 URI
        private String writeAReviewUri; // 리뷰 작성 URI
        private String reviewsUri; // 리뷰 목록 URI
        private String photosUri; // 사진 목록 URI
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PriceRange {
        private Price startPrice; // 시작 가격
        private Price endPrice; // 종료 가격

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Price {
            private String currencyCode; // "USD" 등
            private String units; // "20" 등 (문자열 형식)
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewSummary {
        private TextContent text; // { text, languageCode }
        private String flagContentUri; // 신고 URI
        private TextContent disclosureText; // { text, languageCode }
        private String reviewsUri; // 리뷰 URI

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class TextContent {
            private String text;
            private String languageCode;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeZone {
        private String id; // "America/New_York" 등
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PostalAddress {
        private String regionCode; // "US" 등
        private String languageCode; // "en-US" 등
        private String postalCode; // "10038-1510" 등
        private String administrativeArea; // "New York" 등
        private String locality; // "New York" 등
        private List<String> addressLines; // ["25 Cedar St"] 등
    }
}

