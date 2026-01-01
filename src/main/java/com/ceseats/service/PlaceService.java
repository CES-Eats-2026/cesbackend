package com.ceseats.service;

import com.ceseats.dto.request.PlaceSearchRequest;
import com.ceseats.dto.response.PlaceResponse;
import com.ceseats.dto.response.PlaceSearchResponse;
import com.ceseats.entity.PlaceView;
import com.ceseats.repository.PlaceViewRepository;
import com.ceseats.service.cache.CacheService;
import com.ceseats.service.google.GooglePlacesClient;
import com.ceseats.service.google.PlaceDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 장소 검색 및 추천 메인 서비스
 */
@Service
public class PlaceService {

    @Autowired
    private GooglePlacesClient googlePlacesClient;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private PlaceViewRepository placeViewRepository;
    
    // 병렬 처리를 위한 스레드 풀 (최대 10개 동시 요청)
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double WALKING_SPEED_KMH = 5.0; // 도보 속도 5km/h

    /**
     * 장소 검색
     */
    public PlaceSearchResponse searchPlaces(PlaceSearchRequest request, double userLatitude, double userLongitude) {
        // 1. 캐시에서 Nearby Search 결과 확인
        List<String> placeIds = cacheService.getNearbyPlaces(
                request.getLatitude(), 
                request.getLongitude(), 
                request.getRadius()
        );

        // 2. 캐시에 없으면 Google Places API 호출
        if (placeIds == null || placeIds.isEmpty()) {
            placeIds = googlePlacesClient.searchNearbyPlaces(
                    request.getLatitude(),
                    request.getLongitude(),
                    request.getRadius()
            );
            // 캐시에 저장
            cacheService.setNearbyPlaces(
                    request.getLatitude(),
                    request.getLongitude(),
                    request.getRadius(),
                    placeIds
            );
        }

        // 3. 각 place_id에 대해 Place Details 가져오기 (병렬 처리로 성능 개선)
        List<CompletableFuture<PlaceResponse>> futures = new ArrayList<>();
        for (String placeId : placeIds) {
            CompletableFuture<PlaceResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // 캐시에서 Place Details 확인
                    PlaceDetails details = cacheService.getPlaceDetails(placeId, PlaceDetails.class);

                    // 캐시에 없으면 API 호출
                    if (details == null) {
                        details = googlePlacesClient.getPlaceDetails(placeId);
                        if (details != null) {
                            cacheService.setPlaceDetails(placeId, details);
                        }
                    }

                    if (details != null) {
                        // 도보 시간 계산
                        int walkTimeMinutes = calculateWalkTime(
                                userLatitude,
                                userLongitude,
                                details.getLatitude(),
                                details.getLongitude()
                        );

                        // 조회수 가져오기
                        Long viewCount = getViewCount(placeId);

                        // PlaceResponse로 변환 (사진 URL 생성 포함)
                        return convertToPlaceResponse(details, walkTimeMinutes, viewCount);
                    }
                    return null;
                } catch (Exception e) {
                    System.err.println("Error processing place " + placeId + ": " + e.getMessage());
                    return null;
                }
            }, executorService);
            futures.add(future);
        }

        // 모든 비동기 작업 완료 대기 및 결과 수집
        List<PlaceResponse> places = futures.stream()
                .map(CompletableFuture::join)
                .filter(place -> place != null)
                .collect(Collectors.toList());

        // Hook 메시지 생성 제거 (추천 메커니즘 제거)
        // 모든 장소를 동일하게 표시

        // 5. 정렬
        if ("price_asc".equals(request.getSortBy())) {
            places.sort(Comparator.comparing(PlaceResponse::getPriceLevel));
        } else if ("view_desc".equals(request.getSortBy())) {
            places.sort(Comparator.comparing(PlaceResponse::getViewCount, Comparator.reverseOrder()));
        }

        return new PlaceSearchResponse(places, places.size());
    }

    /**
     * 장소 조회수 증가 (카드 클릭 시)
     */
    @Transactional
    public void incrementViewCount(String placeId) {
        Optional<PlaceView> placeViewOpt = placeViewRepository.findByPlaceId(placeId);
        
        if (placeViewOpt.isPresent()) {
            PlaceView placeView = placeViewOpt.get();
            placeView.incrementViewCount();
            placeViewRepository.save(placeView);
        } else {
            PlaceView placeView = new PlaceView();
            placeView.setPlaceId(placeId);
            placeView.setViewCount(1L);
            placeViewRepository.save(placeView);
        }
    }

    /**
     * 조회수 가져오기
     */
    private Long getViewCount(String placeId) {
        Optional<PlaceView> placeViewOpt = placeViewRepository.findByPlaceId(placeId);
        return placeViewOpt.map(PlaceView::getViewCount).orElse(0L);
    }

    /**
     * PlaceDetails를 PlaceResponse로 변환
     */
    private PlaceResponse convertToPlaceResponse(PlaceDetails details, int walkTimeMinutes, Long viewCount) {
        // 타입 결정 (Google Places API types 기반)
        String type = determinePlaceType(details.getTypes());
        
        PlaceResponse.PlaceResponseBuilder builder = PlaceResponse.builder()
                .id(details.getPlaceId())
                .name(details.getName())
                .walkTimeMinutes(walkTimeMinutes)
                .priceLevel(convertPriceLevel(details.getPriceLevel()))
                .openNow(details.getOpenNow())
                .busyLevel(details.getBusyLevel() != null ? details.getBusyLevel() : "UNKNOWN")
                .rating(details.getRating())
                .reviewCount(details.getReviewCount())
                .oneLineSummary(details.getOneLineSummary())
                .googleMapUrl(googlePlacesClient.generateGoogleMapUrl(details.getPlaceId()))
                .viewCount(viewCount)
                .latitude(details.getLatitude())
                .longitude(details.getLongitude())
                .address(details.getAddress())
                .type(type)
                .website(details.getWebsite());

        // 리뷰 변환
        if (details.getReviews() != null) {
            List<PlaceResponse.ReviewDto> reviewDtos = details.getReviews().stream()
                    .map(review -> new PlaceResponse.ReviewDto(
                            review.getAuthorName(),
                            review.getRating(),
                            review.getText(),
                            review.getTime(),
                            review.getRelativeTimeDescription()
                    ))
                    .collect(Collectors.toList());
            builder.reviews(reviewDtos);
        }

        // 평균 가격 추정 (price_level 기반)
        if (details.getPriceLevel() != null) {
            double estimatedPrice = estimateAveragePrice(details.getPriceLevel());
            builder.averagePriceEstimate(estimatedPrice);
        }

        // 사진 URL 생성 (신규 API 우선, 기존 API fallback)
        if (details.getPhotos() != null && !details.getPhotos().isEmpty()) {
            // 신규 API의 photos 리스트 사용
            List<String> photoUrls = details.getPhotos().stream()
                    .map(photoInfo -> googlePlacesClient.generatePhotoUrl(
                            photoInfo.getName(),
                            photoInfo.getPhotoReference(),
                            details.getPlaceId(),
                            400
                    ))
                    .filter(url -> url != null)
                    .limit(5) // 최대 5개
                    .collect(Collectors.toList());
            builder.photos(photoUrls);
        } else if (details.getPhotoReferences() != null && !details.getPhotoReferences().isEmpty()) {
            // 기존 API fallback: photo_reference 사용
            List<String> photoUrls = details.getPhotoReferences().stream()
                    .map(ref -> googlePlacesClient.generatePhotoUrl(ref, 400))
                    .filter(url -> url != null)
                    .limit(5) // 최대 5개
                    .collect(Collectors.toList());
            builder.photos(photoUrls);
        }

        return builder.build();
    }

    /**
     * 가격 수준을 "$", "$$", "$$$" 형식으로 변환
     */
    private String convertPriceLevel(Integer priceLevel) {
        if (priceLevel == null) {
            return "$$";
        }
        // Google API: 0=무료, 1=저렴, 2=보통, 3=비쌈, 4=매우비쌈
        // 우리 형식: $=저렴, $$=보통, $$$=비쌈
        if (priceLevel <= 1) {
            return "$";
        } else if (priceLevel == 2) {
            return "$$";
        } else {
            return "$$$";
        }
    }

    /**
     * 평균 가격 추정 (USD 기준)
     */
    private Double estimateAveragePrice(Integer priceLevel) {
        // Google price_level 기반 추정
        // 0-1: $10-20, 2: $20-40, 3: $40-60, 4: $60+
        switch (priceLevel) {
            case 0:
            case 1:
                return 15.0;
            case 2:
                return 30.0;
            case 3:
                return 50.0;
            case 4:
                return 70.0;
            default:
                return 30.0;
        }
    }

    /**
     * 도보 시간 계산 (분 단위)
     */
    private int calculateWalkTime(double lat1, double lon1, double lat2, double lon2) {
        double distanceKm = calculateDistance(lat1, lon1, lat2, lon2);
        double timeHours = distanceKm / WALKING_SPEED_KMH;
        return (int) Math.round(timeHours * 60); // 분으로 변환
    }

    /**
     * 두 지점 간 거리 계산 (Haversine 공식)
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * placeId로 PlaceDetails 찾기
     */
    private PlaceDetails findPlaceDetailsById(List<PlaceDetails> places, String placeId) {
        return places.stream()
                .filter(p -> p.getPlaceId().equals(placeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Google Places API types를 기반으로 장소 타입 결정
     * Google Places API의 타입을 그대로 사용 (간단한 매핑만 수행)
     */
    private String determinePlaceType(List<String> types) {
        if (types == null || types.isEmpty()) {
            return "other"; // 타입이 없으면 "기타"
        }

        // Google Places API 타입을 그대로 사용하되, 일부만 매핑
        // meal_takeaway -> fastfood로 매핑
        for (String type : types) {
            if (type.equals("meal_takeaway") || type.equals("fast_food")) {
                return "fastfood";
            }
        }

        // 나머지는 Google Places API 타입을 그대로 사용
        // 지원하는 타입: restaurant, cafe, bar, food, bakery, meal_delivery,
        //               night_club, liquor_store, store, shopping_mall, supermarket, convenience_store
        for (String type : types) {
            if (type.equals("restaurant") || type.equals("cafe") || type.equals("bar") ||
                type.equals("food") || type.equals("bakery") || type.equals("meal_delivery") ||
                type.equals("night_club") || type.equals("liquor_store") ||
                type.equals("store") || type.equals("shopping_mall") ||
                type.equals("supermarket") || type.equals("convenience_store")) {
                return type;
            }
        }

        // 지원하는 타입이 없으면 "기타"
        return "other";
    }
}

