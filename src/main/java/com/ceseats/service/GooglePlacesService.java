package com.ceseats.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Service
public class GooglePlacesService {

    @Value("${google.places.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LLMService llmService;

    private static final String PLACES_API_BASE_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json";
    private static final String PLACE_DETAILS_API_BASE_URL = "https://maps.googleapis.com/maps/api/place/details/json";

    public GooglePlacesService(LLMService llmService) {
        this.llmService = llmService;
    }

    public List<PlaceInfo> searchNearbyPlaces(double latitude, double longitude, int radius, String type) {
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("⚠️  Warning: Google Places API key is not set.");
            System.err.println("   환경 변수 GOOGLE_PLACES_API_KEY를 설정해주세요.");
            System.err.println("   로컬: export GOOGLE_PLACES_API_KEY=your_api_key");
            System.err.println("   프로덕션: GitHub Secrets 또는 서버 환경 변수에 설정 필요");
            return new ArrayList<>();
        }

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

                List<PlaceInfo> places = new ArrayList<>();
                if (results != null && results.isArray()) {
                    for (JsonNode result : results) {
                        PlaceInfo place = parsePlaceResult(result);
                        if (place != null) {
                            places.add(place);
                        }
                    }
                }
                return places;
            }
        } catch (Exception e) {
            System.err.println("Error fetching places from Google Places API: " + e.getMessage());
            e.printStackTrace();
        }

        return new ArrayList<>();
    }

    private PlaceInfo parsePlaceResult(JsonNode result) {
        try {
            String name = result.get("name").asText();
            String placeId = result.has("place_id") ? result.get("place_id").asText() : null;
            
            JsonNode geometry = result.get("geometry");
            JsonNode location = geometry.get("location");
            double lat = location.get("lat").asDouble();
            double lng = location.get("lng").asDouble();

            String address = null;
            if (result.has("vicinity")) {
                address = result.get("vicinity").asText();
            } else if (result.has("formatted_address")) {
                address = result.get("formatted_address").asText();
            }

            int priceLevel = 2; // 기본값
            if (result.has("price_level")) {
                priceLevel = result.get("price_level").asInt() + 1; // Google은 0-4, 우리는 1-3
                if (priceLevel > 3) priceLevel = 3;
                if (priceLevel < 1) priceLevel = 1;
            }

            // 타입 결정
            String placeType = determineStoreType(result);

            // Place Details API로 리뷰와 설명 가져오기
            String description = null;
            String reviews = null;
            if (placeId != null) {
                PlaceDetails details = getPlaceDetails(placeId);
                if (details != null) {
                    description = details.getDescription();
                    reviews = details.getReviews();
                }
            }

            // LLM으로 CES 이유 생성
            String cesReason = llmService.generateCesReason(name, placeType, reviews, description);

            return new PlaceInfo(name, placeType, lat, lng, address, priceLevel, cesReason);
        } catch (Exception e) {
            System.err.println("Error parsing place result: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private PlaceDetails getPlaceDetails(String placeId) {
        if (apiKey == null || apiKey.isEmpty() || placeId == null) {
            return null;
        }

        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(PLACE_DETAILS_API_BASE_URL)
                    .queryParam("place_id", placeId)
                    .queryParam("fields", "editorial_summary,reviews,description")
                    .queryParam("key", apiKey);

            ResponseEntity<String> response = restTemplate.getForEntity(uriBuilder.toUriString(), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode result = root.get("result");
                
                if (result != null) {
                    String description = null;
                    if (result.has("editorial_summary") && result.get("editorial_summary").has("overview")) {
                        description = result.get("editorial_summary").get("overview").asText();
                    } else if (result.has("description")) {
                        description = result.get("description").asText();
                    }

                    // 리뷰 요약 (최대 3개)
                    StringBuilder reviewsBuilder = new StringBuilder();
                    if (result.has("reviews") && result.get("reviews").isArray()) {
                        JsonNode reviewsArray = result.get("reviews");
                        int reviewCount = Math.min(3, reviewsArray.size());
                        for (int i = 0; i < reviewCount; i++) {
                            JsonNode review = reviewsArray.get(i);
                            if (review.has("text")) {
                                String reviewText = review.get("text").asText();
                                // 리뷰 텍스트를 200자로 제한
                                if (reviewText.length() > 200) {
                                    reviewText = reviewText.substring(0, 200) + "...";
                                }
                                reviewsBuilder.append(reviewText);
                                if (i < reviewCount - 1) {
                                    reviewsBuilder.append(" ");
                                }
                            }
                        }
                    }
                    
                    return new PlaceDetails(description, reviewsBuilder.toString());
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching place details: " + e.getMessage());
            // 에러가 발생해도 계속 진행 (fallback 사용)
        }

        return null;
    }

    private static class PlaceDetails {
        private final String description;
        private final String reviews;

        public PlaceDetails(String description, String reviews) {
            this.description = description;
            this.reviews = reviews;
        }

        public String getDescription() {
            return description;
        }

        public String getReviews() {
            return reviews;
        }
    }

    private String determineStoreType(JsonNode result) {
        if (!result.has("types")) {
            return "restaurant";
        }

        JsonNode types = result.get("types");
        for (JsonNode type : types) {
            String typeStr = type.asText();
            if (typeStr.equals("restaurant") || typeStr.equals("food") || typeStr.equals("meal_takeaway")) {
                return "restaurant";
            } else if (typeStr.equals("cafe") || typeStr.equals("bakery")) {
                return "cafe";
            } else if (typeStr.equals("bar") || typeStr.equals("night_club")) {
                return "bar";
            } else if (typeStr.contains("fast") || typeStr.equals("meal_delivery")) {
                return "fastfood";
            }
        }
        return "restaurant";
    }


    public static class PlaceInfo {
        private final String name;
        private final String type;
        private final double latitude;
        private final double longitude;
        private final String address;
        private final int priceLevel;
        private final String cesReason;

        public PlaceInfo(String name, String type, double latitude, double longitude, 
                        String address, int priceLevel, String cesReason) {
            this.name = name;
            this.type = type;
            this.latitude = latitude;
            this.longitude = longitude;
            this.address = address;
            this.priceLevel = priceLevel;
            this.cesReason = cesReason;
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public String getAddress() { return address; }
        public int getPriceLevel() { return priceLevel; }
        public String getCesReason() { return cesReason; }
    }
}

