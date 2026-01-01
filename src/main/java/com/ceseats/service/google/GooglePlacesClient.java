package com.ceseats.service.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Places API 클라이언트 래퍼
 * Nearby Search API와 Place Details API를 사용
 */
@Service
public class GooglePlacesClient {

    @Value("${google.places.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PLACES_API_BASE_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json";
    private static final String PLACE_DETAILS_API_BASE_URL = "https://maps.googleapis.com/maps/api/place/details/json";

    /**
     * Nearby Search API로 주변 장소 검색
     * restaurant와 cafe 타입을 각각 호출하여 결과 합침
     * @param latitude 위도
     * @param longitude 경도
     * @param radius 반경 (미터)
     * @return place_id 리스트
     */
    public List<String> searchNearbyPlaces(double latitude, double longitude, int radius) {
        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("Warning: Google Places API key is not set.");
            return new ArrayList<>();
        }

        List<String> allPlaceIds = new ArrayList<>();
        
        // 모든 타입의 장소를 가져오기 위해 여러 타입을 각각 호출
        // Google Places API는 type 파라미터 없이 호출하면 제한된 결과만 반환하므로
        // 가능한 모든 타입을 포함하여 호출
        String[] types = {
            // 음식 관련
            "restaurant", "cafe", "meal_takeaway", "bar", "food", "bakery", "meal_delivery",
            // 쇼핑 관련
            "store", "shopping_mall", "supermarket", "convenience_store", "clothing_store", "shoe_store",
            // 엔터테인먼트
            "night_club", "movie_theater", "amusement_park", "zoo", "museum", "art_gallery",
            // 기타
            "liquor_store", "gas_station", "parking", "lodging", "tourist_attraction",
            "point_of_interest", "establishment" // 일반 POI
        };
        
        for (String type : types) {
            try {
                UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(PLACES_API_BASE_URL)
                        .queryParam("location", latitude + "," + longitude)
                        .queryParam("radius", radius)
                        .queryParam("type", type)
                        .queryParam("key", apiKey);

                ResponseEntity<String> response = restTemplate.getForEntity(uriBuilder.toUriString(), String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    JsonNode results = root.get("results");

                    if (results != null && results.isArray()) {
                        for (JsonNode result : results) {
                            if (result.has("place_id")) {
                                String placeId = result.get("place_id").asText();
                                if (!allPlaceIds.contains(placeId)) {
                                    allPlaceIds.add(placeId);
                                }
                            }
                        }
                    }
                    
                    // next_page_token이 있으면 다음 페이지도 가져오기 (각 타입당 최대 2페이지)
                    JsonNode nextPageToken = root.get("next_page_token");
                    int pageCount = 1;
                    while (nextPageToken != null && nextPageToken.asText() != null && !nextPageToken.asText().isEmpty() && pageCount < 2) {
                        try {
                            // next_page_token 사용 시 약간의 지연 필요 (Google API 요구사항)
                            Thread.sleep(2000);
                            
                            UriComponentsBuilder nextPageBuilder = UriComponentsBuilder.fromHttpUrl(PLACES_API_BASE_URL)
                                    .queryParam("pagetoken", nextPageToken.asText())
                                    .queryParam("key", apiKey);
                            
                            ResponseEntity<String> nextResponse = restTemplate.getForEntity(nextPageBuilder.toUriString(), String.class);
                            
                            if (nextResponse.getStatusCode().is2xxSuccessful() && nextResponse.getBody() != null) {
                                JsonNode nextRoot = objectMapper.readTree(nextResponse.getBody());
                                JsonNode nextResults = nextRoot.get("results");
                                
                                if (nextResults != null && nextResults.isArray()) {
                                    for (JsonNode result : nextResults) {
                                        if (result.has("place_id")) {
                                            String placeId = result.get("place_id").asText();
                                            if (!allPlaceIds.contains(placeId)) {
                                                allPlaceIds.add(placeId);
                                            }
                                        }
                                    }
                                }
                                
                                nextPageToken = nextRoot.get("next_page_token");
                                pageCount++;
                            } else {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            System.err.println("Error fetching next page for type " + type + ": " + e.getMessage());
                            break;
                        }
                    }
                }
                
                // API 호출 제한을 피하기 위해 약간의 지연
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error fetching places from Google Places API (type: " + type + "): " + e.getMessage());
                // 에러가 발생해도 다음 타입 계속 시도
            }
        }

        return allPlaceIds;
    }

    /**
     * Place Details API로 장소 상세 정보 가져오기
     */
    public PlaceDetails getPlaceDetails(String placeId) {
        if (apiKey == null || apiKey.isEmpty() || placeId == null) {
            return null;
        }

        try {
            // 필요한 필드만 요청하여 비용 절감
            String fields = "name,place_id,geometry,opening_hours,price_level,rating," +
                    "user_ratings_total,photos,editorial_summary,reviews,formatted_address," +
                    "current_opening_hours,price_level,types,website";

            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(PLACE_DETAILS_API_BASE_URL)
                    .queryParam("place_id", placeId)
                    .queryParam("fields", fields)
                    .queryParam("key", apiKey);

            ResponseEntity<String> response = restTemplate.getForEntity(uriBuilder.toUriString(), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode result = root.get("result");

                if (result != null) {
                    return parsePlaceDetails(result);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching place details: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Place Details JSON 파싱
     */
    private PlaceDetails parsePlaceDetails(JsonNode result) {
        PlaceDetails details = new PlaceDetails();

        // 기본 정보
        if (result.has("place_id")) {
            details.setPlaceId(result.get("place_id").asText());
        }
        if (result.has("name")) {
            details.setName(result.get("name").asText());
        }

        // 위치 정보
        if (result.has("geometry") && result.get("geometry").has("location")) {
            JsonNode location = result.get("geometry").get("location");
            if (location.has("lat")) {
                details.setLatitude(location.get("lat").asDouble());
            }
            if (location.has("lng")) {
                details.setLongitude(location.get("lng").asDouble());
            }
        }

        // 주소
        if (result.has("formatted_address")) {
            details.setAddress(result.get("formatted_address").asText());
        }

        // 웹사이트 (메뉴 정보가 있을 수 있음)
        if (result.has("website")) {
            details.setWebsite(result.get("website").asText());
        }

        // 영업 시간
        if (result.has("current_opening_hours")) {
            JsonNode openingHours = result.get("current_opening_hours");
            if (openingHours.has("open_now")) {
                details.setOpenNow(openingHours.get("open_now").asBoolean());
            }
        } else if (result.has("opening_hours")) {
            JsonNode openingHours = result.get("opening_hours");
            if (openingHours.has("open_now")) {
                details.setOpenNow(openingHours.get("open_now").asBoolean());
            }
        }

        // 가격 수준
        if (result.has("price_level")) {
            int priceLevel = result.get("price_level").asInt();
            details.setPriceLevel(priceLevel);
        }

        // 평점 및 리뷰 수
        if (result.has("rating")) {
            details.setRating(result.get("rating").asDouble());
        }
        if (result.has("user_ratings_total")) {
            details.setReviewCount(result.get("user_ratings_total").asLong());
        }

        // 사진 파싱 (기존 API와 신규 API 모두 지원)
        if (result.has("photos") && result.get("photos").isArray()) {
            List<String> photoReferences = new ArrayList<>();
            List<PlaceDetails.PhotoInfo> photoInfos = new ArrayList<>();
            
            for (JsonNode photo : result.get("photos")) {
                PlaceDetails.PhotoInfo photoInfo = new PlaceDetails.PhotoInfo();
                
                // 신규 API: name 필드
                if (photo.has("name")) {
                    photoInfo.setName(photo.get("name").asText());
                }
                
                // 기존 API: photo_reference 필드
                if (photo.has("photo_reference")) {
                    String photoRef = photo.get("photo_reference").asText();
                    photoReferences.add(photoRef);
                    photoInfo.setPhotoReference(photoRef);
                }
                
                // 크기 정보
                if (photo.has("widthPx")) {
                    photoInfo.setWidthPx(photo.get("widthPx").asInt());
                }
                if (photo.has("heightPx")) {
                    photoInfo.setHeightPx(photo.get("heightPx").asInt());
                }
                
                photoInfos.add(photoInfo);
            }
            
            details.setPhotoReferences(photoReferences);
            details.setPhotos(photoInfos);
        }

        // 한 줄 요약
        if (result.has("editorial_summary") && result.get("editorial_summary").has("overview")) {
            details.setOneLineSummary(result.get("editorial_summary").get("overview").asText());
        }

        // 타입 정보
        if (result.has("types") && result.get("types").isArray()) {
            List<String> types = new ArrayList<>();
            for (JsonNode type : result.get("types")) {
                types.add(type.asText());
            }
            details.setTypes(types);
        }

        // 리뷰 (상위 5개 + 최신 5개)
        if (result.has("reviews") && result.get("reviews").isArray()) {
            List<PlaceDetails.Review> reviews = new ArrayList<>();
            JsonNode reviewsArray = result.get("reviews");
            
            // 상위 5개 (rating 높은 순)
            List<PlaceDetails.Review> topReviews = new ArrayList<>();
            for (JsonNode review : reviewsArray) {
                PlaceDetails.Review reviewObj = parseReview(review);
                if (reviewObj != null) {
                    topReviews.add(reviewObj);
                }
            }
            topReviews.sort((a, b) -> Integer.compare(b.getRating(), a.getRating()));
            reviews.addAll(topReviews.stream().limit(5).toList());

            // 최신 5개 (time 순)
            List<PlaceDetails.Review> recentReviews = new ArrayList<>();
            for (JsonNode review : reviewsArray) {
                PlaceDetails.Review reviewObj = parseReview(review);
                if (reviewObj != null) {
                    recentReviews.add(reviewObj);
                }
            }
            recentReviews.sort((a, b) -> Long.compare(b.getTime(), a.getTime()));
            reviews.addAll(recentReviews.stream().limit(5).toList());

            details.setReviews(reviews);
        }

        return details;
    }

    private PlaceDetails.Review parseReview(JsonNode review) {
        try {
            PlaceDetails.Review reviewObj = new PlaceDetails.Review();
            if (review.has("author_name")) {
                reviewObj.setAuthorName(review.get("author_name").asText());
            }
            if (review.has("rating")) {
                reviewObj.setRating(review.get("rating").asInt());
            }
            if (review.has("text")) {
                reviewObj.setText(review.get("text").asText());
            }
            if (review.has("time")) {
                reviewObj.setTime(review.get("time").asLong());
            }
            if (review.has("relative_time_description")) {
                reviewObj.setRelativeTimeDescription(review.get("relative_time_description").asText());
            }
            return reviewObj;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * restaurant, cafe, fastfood, bar 타입인지 확인
     * (더 이상 사용하지 않음 - Place Details에서 정확한 타입 결정)
     */
    @Deprecated
    private boolean isRestaurantOrCafe(JsonNode result) {
        // 모든 결과를 가져온 후 Place Details에서 정확한 타입 결정
        return true;
    }

    /**
     * Google Maps 방향 URL 생성
     */
    public String generateGoogleMapUrl(String placeId) {
        return "https://www.google.com/maps/place/?q=place_id:" + placeId;
    }

    /**
     * Google Places 사진 URL 생성
     * 신규 API와 기존 API 모두 지원
     * @param photoName 신규 API용: places/PLACE_ID/photos/PHOTO_RESOURCE (우선 사용)
     * @param photoReference 기존 API용: photo_reference (fallback)
     * @param placeId place_id (신규 API용)
     * @param maxWidth 최대 너비 (픽셀)
     * @return 사진 URL
     */
    public String generatePhotoUrl(String photoName, String photoReference, String placeId, int maxWidth) {
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        
        // 신규 API 우선 사용 (name이 있으면)
        if (photoName != null && !photoName.isEmpty() && placeId != null) {
            // name 형식: places/PLACE_ID/photos/PHOTO_RESOURCE
            // 또는 단순히 PHOTO_RESOURCE만 있을 수 있음
            String photoResource;
            if (photoName.startsWith("places/")) {
                photoResource = photoName;
            } else {
                photoResource = "places/" + placeId + "/photos/" + photoName;
            }
            
            return String.format(
                "https://places.googleapis.com/v1/%s/media?maxHeightPx=%d&maxWidthPx=%d&key=%s",
                photoResource,
                maxWidth,
                maxWidth,
                apiKey
            );
        }
        
        // 기존 API fallback
        if (photoReference != null && !photoReference.isEmpty()) {
            return String.format(
                "https://maps.googleapis.com/maps/api/place/photo?maxwidth=%d&photoreference=%s&key=%s",
                maxWidth,
                photoReference,
                apiKey
            );
        }
        
        return null;
    }
    
    /**
     * 기존 메서드 호환성 유지
     */
    public String generatePhotoUrl(String photoReference, int maxWidth) {
        return generatePhotoUrl(null, photoReference, null, maxWidth);
    }
}

