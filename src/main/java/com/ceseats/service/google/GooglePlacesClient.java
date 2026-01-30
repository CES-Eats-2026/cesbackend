package com.ceseats.service.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ceseats.dto.request.SearchNearbyRequest;
import com.ceseats.dto.response.SearchNearbyResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String PLACES_V1_SEARCH_NEARBY_URL = "https://places.googleapis.com/v1/places:searchNearby";

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
            System.err.println("⚠️  Warning: Google Places API key is not set.");
            System.err.println("   환경 변수 GOOGLE_PLACES_API_KEY를 설정해주세요.");
            System.err.println("   로컬: export GOOGLE_PLACES_API_KEY=your_api_key");
            System.err.println("   프로덕션: GitHub Secrets 또는 서버 환경 변수에 설정 필요");
            return new ArrayList<>();
        }

        // 핵심 타입만 사용하여 성능 개선 (음식 관련 타입 위주)
        // 타입을 줄여서 API 호출 수 감소 및 병렬 처리로 속도 개선
        String[] types = {
            // 음식 관련 (핵심)
            "restaurant", "cafe", "meal_takeaway", "bar", "food", "bakery",
            // 쇼핑 관련 (필수)
            "store", "shopping_mall", "supermarket", "convenience_store"
        };
        
        // 병렬 처리를 위한 ExecutorService
        ExecutorService executorService = Executors.newFixedThreadPool(Math.min(types.length, 10));
        List<CompletableFuture<List<String>>> futures = new ArrayList<>();
        
        for (String type : types) {
            CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
                List<String> placeIds = new ArrayList<>();
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
                                    if (!placeIds.contains(placeId)) {
                                        placeIds.add(placeId);
                                    }
                                }
                            }
                        }
                        
                        // 첫 페이지만 가져오기 (성능 개선을 위해 next_page_token 사용 안 함)
                        // 필요시 주석 해제하여 사용
                        /*
                        JsonNode nextPageToken = root.get("next_page_token");
                        if (nextPageToken != null && nextPageToken.asText() != null && !nextPageToken.asText().isEmpty()) {
                            try {
                                Thread.sleep(2000); // Google API 요구사항
                                // 다음 페이지 로직...
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        */
                    }
                } catch (Exception e) {
                    System.err.println("Error fetching places from Google Places API (type: " + type + "): " + e.getMessage());
                }
                return placeIds;
            }, executorService);
            futures.add(future);
        }

        // 모든 병렬 작업 완료 대기 및 결과 수집
        Set<String> allPlaceIdsSet = new HashSet<>();
        for (CompletableFuture<List<String>> future : futures) {
            try {
                List<String> placeIds = future.join();
                allPlaceIdsSet.addAll(placeIds);
            } catch (Exception e) {
                System.err.println("Error joining future: " + e.getMessage());
            }
        }
        
        executorService.shutdown();
        
        return new ArrayList<>(allPlaceIdsSet);
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

            System.out.println("Fetching Place Details for: " + placeId);
            ResponseEntity<String> response = restTemplate.getForEntity(uriBuilder.toUriString(), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                // 에러 응답 확인
                if (root.has("error_message")) {
                    System.err.println("Place Details API error for " + placeId + ": " + root.get("error_message").asText());
                    return null;
                }
                
                JsonNode result = root.get("result");

                if (result != null) {
                    System.out.println("Successfully fetched Place Details for: " + placeId);
                    return parsePlaceDetails(result);
                } else {
                    System.err.println("Place Details API returned no result for: " + placeId);
                    System.err.println("Response body: " + response.getBody());
                }
            } else {
                System.err.println("Place Details API returned non-2xx status for " + placeId + ": " + response.getStatusCode());
                System.err.println("Response body: " + response.getBody());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            System.err.println("HTTP Error fetching place details for " + placeId + ": " + e.getStatusCode());
            System.err.println("Error response: " + e.getResponseBodyAsString());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Error fetching place details for " + placeId + ": " + e.getMessage());
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

    /**
     * Google Places API v1 searchNearby 사용
     * @param latitude 위도
     * @param longitude 경도
     * @param radius 반경 (미터)
     * @param includedTypes 포함할 타입 리스트 (예: ["restaurant", "cafe"])
     * @param maxResultCount 최대 결과 수
     * @return Place 객체 리스트 (전체 정보 포함, Place Details API 호출 불필요)
     */
    public List<SearchNearbyResponse.Place> searchNearbyV1(double latitude, double longitude, double radius, List<String> includedTypes, int maxResultCount) {
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Warning: Google Places API key is not set.");
            return new ArrayList<>();
        }

        try {
            //요청 본문 생성
            SearchNearbyRequest request = new SearchNearbyRequest();

            //각 카테고리당 최대 50개 타입 제한
            if (includedTypes == null || includedTypes.isEmpty()) {
                //음식점 및 쇼핑 관련 Table A 타입 포함 (최대 50개)
                request.setIncludedTypes(List.of(
                    "bakery", "bar", "cafe", "restaurant", "meal_delivery", "meal_takeaway", "night_club",
                    "shopping_mall", "supermarket", "convenience_store", "store", "department_store",
                    "clothing_store", "shoe_store", "jewelry_store", "electronics_store", "furniture_store",
                    "home_goods_store", "hardware_store", "book_store", "pet_store", "liquor_store",
                    "tourist_attraction", "park", "museum", "art_gallery", "casino",
                    "lodging", "spa", "gym", "pharmacy", "hospital", "bank", "atm",
                    "gas_station", "parking", "subway_station", "train_station", "bus_station", "airport",
                    "church", "hindu_temple", "mosque", "synagogue",
                    "school", "university", "library", "zoo", "aquarium", "amusement_park"
                ));
            } else {
                request.setIncludedTypes(includedTypes);
            }

            request.setMaxResultCount(maxResultCount);
            request.setRankPreference("POPULARITY");//추천의 성격 부여
            
            SearchNearbyRequest.LocationRestriction locationRestriction = new SearchNearbyRequest.LocationRestriction();
            SearchNearbyRequest.LocationRestriction.Circle circle = new SearchNearbyRequest.LocationRestriction.Circle();
            SearchNearbyRequest.LocationRestriction.Circle.Center center = new SearchNearbyRequest.LocationRestriction.Circle.Center();
            center.setLatitude(latitude);
            center.setLongitude(longitude);
            circle.setCenter(center);
            circle.setRadius(radius);
            locationRestriction.setCircle(circle);
            request.setLocationRestriction(locationRestriction);

            //헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", apiKey);
            //필요한 필드만 요청하여 응답 용량 줄이기
            headers.set("X-Goog-FieldMask",
                "places.id," +
                "places.displayName," +
                "places.location," +
                "places.types," +
                "places.googleMapsUri," +
                "places.formattedAddress," +
                "places.generativeSummary,");

            HttpEntity<SearchNearbyRequest> entity = new HttpEntity<>(request, headers);

            //API 호출 (String으로 먼저 받아서 원본 JSON 확인)
            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    PLACES_V1_SEARCH_NEARBY_URL,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            
            //원본 JSON 응답 로깅 (처음 3개 장소의 location 구조 확인)
            if (rawResponse.getStatusCode().is2xxSuccessful() && rawResponse.getBody() != null) {
                try {
                    JsonNode rootNode = objectMapper.readTree(rawResponse.getBody());
                    JsonNode placesNode = rootNode.get("places");
                    if (placesNode != null && placesNode.isArray()) {
                        int logCount = Math.min(3, placesNode.size());
                        System.out.println("=== Raw JSON Response - Location Structure (first " + logCount + " places) ===");
                        for (int i = 0; i < logCount; i++) {
                            JsonNode placeNode = placesNode.get(i);
                            System.out.println("Place " + (i + 1) + ":");
                            System.out.println("  - id: " + (placeNode.has("id") ? placeNode.get("id").asText() : "null"));
                            if (placeNode.has("location")) {
                                JsonNode locationNode = placeNode.get("location");
                                System.out.println("  - location (raw JSON): " + locationNode.toString());
                                System.out.println("  - location.has('latitude'): " + locationNode.has("latitude"));
                                System.out.println("  - location.has('longitude'): " + locationNode.has("longitude"));
                                System.out.println("  - location.has('latLng'): " + locationNode.has("latLng"));
                                if (locationNode.has("latitude")) {
                                    System.out.println("  - location.latitude: " + locationNode.get("latitude").asDouble());
                                }
                                if (locationNode.has("longitude")) {
                                    System.out.println("  - location.longitude: " + locationNode.get("longitude").asDouble());
                                }
                                if (locationNode.has("latLng")) {
                                    System.out.println("  - location.latLng: " + locationNode.get("latLng").toString());
                                }
                            } else {
                                System.out.println("  - location: null (not present in response)");
                            }
                        }
                        System.out.println("=== End Raw JSON ===");
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing raw JSON for debugging: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            //String 응답을 SearchNearbyResponse로 변환
            SearchNearbyResponse responseBody = null;
            if (rawResponse.getStatusCode().is2xxSuccessful() && rawResponse.getBody() != null) {
                try {
                    responseBody = objectMapper.readValue(rawResponse.getBody(), SearchNearbyResponse.class);
                } catch (Exception e) {
                    System.err.println("Error converting JSON to SearchNearbyResponse: " + e.getMessage());
                    e.printStackTrace();
                    return new ArrayList<>();
                }
            }
            
            // ResponseEntity로 래핑
            ResponseEntity<SearchNearbyResponse> response = new ResponseEntity<>(
                    responseBody,
                    rawResponse.getHeaders(),
                    rawResponse.getStatusCode()
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                //responseBody는 이미 위에서 선언됨

                if (responseBody.getPlaces() != null) {
                    System.out.println("Places count: " + responseBody.getPlaces().size());
                    for (SearchNearbyResponse.Place place : responseBody.getPlaces()) {
                        System.out.println("  - Place ID: " + place.getId());
                        System.out.println("  - DisplayName: " + (place.getDisplayName() != null ? place.getDisplayName().getText() : "null"));
                        System.out.println("  - Location: " + (place.getLocation() != null ? 
                            (place.getLocation().getLatitude() != null && place.getLocation().getLongitude() != null ? 
                                place.getLocation().getLatitude() + ", " + place.getLocation().getLongitude() : "null") : "null"));
                    }
                } else {
                    System.out.println("Places is null in response");
                }
                System.out.println("=== End Response ===");
                
                // 전체 Place 객체 리스트 반환 (Place Details API 호출 불필요)
                return responseBody.getPlaces() != null ? responseBody.getPlaces() : new ArrayList<>();
            } else {
                System.err.println("Google Places API v1 returned non-2xx status: " + response.getStatusCode());
                // 응답 본문도 로깅
                try {
                    ResponseEntity<String> errorResponse = restTemplate.exchange(
                            PLACES_V1_SEARCH_NEARBY_URL,
                            HttpMethod.POST,
                            entity,
                            String.class
                    );
                    System.err.println("Error response body: " + errorResponse.getBody());
                } catch (Exception ex) {
                    System.err.println("Could not read error response body: " + ex.getMessage());
                }
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 403) {
                String errorBody = e.getResponseBodyAsString();
                String errorMessage;
                
                // Places API (New)가 활성화되지 않은 경우
                if (errorBody != null && errorBody.contains("SERVICE_DISABLED") && errorBody.contains("places.googleapis.com")) {
                    errorMessage = "❌ Places API (New) is not enabled in your Google Cloud project.\n" +
                            "Please enable Places API (New) in Google Cloud Console:\n" +
                            "https://console.cloud.google.com/apis/api/places.googleapis.com/overview\n" +
                            "Error details: " + e.getMessage();
                    System.err.println(errorMessage);
                    throw new RuntimeException("Places API (New) is not enabled. " +
                            "Please enable it in Google Cloud Console: " +
                            "https://console.cloud.google.com/apis/api/places.googleapis.com/overview", e);
                }
                // Billing이 활성화되지 않은 경우
                else if (errorBody != null && errorBody.contains("BILLING_DISABLED")) {
                    errorMessage = "❌ Google Places API v1 requires billing to be enabled.\n" +
                            "Please enable billing in Google Cloud Console:\n" +
                            "https://console.cloud.google.com/billing\n" +
                            "Error details: " + e.getMessage();
                    System.err.println(errorMessage);
                    throw new RuntimeException("Google Places API v1 billing not enabled. " +
                            "Please enable billing in Google Cloud Console.", e);
                }
                // 기타 403 에러
                else {
                    errorMessage = "❌ Google Places API v1 returned 403 Forbidden.\n" +
                            "Error details: " + e.getMessage();
                    System.err.println(errorMessage);
                    throw new RuntimeException("Google Places API v1 access denied: " + e.getMessage(), e);
                }
            } else {
                System.err.println("Error fetching places from Google Places API v1: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.err.println("Error fetching places from Google Places API v1: " + e.getMessage());
            e.printStackTrace();
        }

        return new ArrayList<>();
    }
}

