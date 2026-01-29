package com.ceseats.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Google Places API v1에서 받은 장소 데이터 DTO.
 * Store 엔티티 + Redis(types) 저장용만 사용.
 *
 * <p>Store 매핑: placeId←id, name←displayName.text|displayNameText|name|id,
 * latitude/longitude←location, address←formattedAddress, link←googleMapsUri,
 * review←reviewSummary.text.text|editorialSummary|reviews+LLM. types→Redis.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaceDataRequest {
    private String id;
    private DisplayName displayName;
    private String displayNameText;
    private String name;
    private Location location;
    private String formattedAddress;
    private String googleMapsUri;
    private List<String> types;
    private ReviewSummary reviewSummary;

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
    public static class DisplayName {
        private String text;
        private String languageCode;
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
}

