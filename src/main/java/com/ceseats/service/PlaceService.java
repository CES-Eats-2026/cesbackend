package com.ceseats.service;

import com.ceseats.dto.request.PlaceSearchRequest;
import com.ceseats.dto.response.PlaceResponse;
import com.ceseats.dto.response.PlaceSearchResponse;
import com.ceseats.entity.PlaceView;
import com.ceseats.model.Store;
import com.ceseats.repository.PlaceViewRepository;
import com.ceseats.repository.StoreRepository;
import com.ceseats.service.cache.CacheService;
import com.ceseats.service.google.GooglePlacesClient;
import com.ceseats.service.google.PlaceDetails;
import com.ceseats.service.LLMService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private LLMService llmService;
    
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
        // 우선순위: DB > 캐시 > Google Places API
        List<CompletableFuture<PlaceResponse>> futures = new ArrayList<>();
        for (String placeId : placeIds) {
            CompletableFuture<PlaceResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    PlaceDetails details = null;
                    boolean fromDatabase = false;

                    // 1. DB에서 먼저 확인
                    Optional<Store> storeOpt = storeRepository.findByPlaceId(placeId);
                    if (storeOpt.isPresent()) {
                        details = convertStoreToPlaceDetails(storeOpt.get());
                        fromDatabase = true; // DB에서 가져왔음을 표시
                    }

                    // 2. DB에 없으면 캐시에서 확인
                    if (details == null) {
                        details = cacheService.getPlaceDetails(placeId, PlaceDetails.class);
                    }

                    // 3. 캐시에도 없으면 Google Places API 호출
                    if (details == null) {
                        details = googlePlacesClient.getPlaceDetails(placeId);
                        if (details != null) {
                            // 캐시에 저장
                            cacheService.setPlaceDetails(placeId, details);
                            // DB에 저장
                            saveStoreToDatabase(details);
                        }
                    } else if (fromDatabase && (details.getPhotos() == null || details.getPhotos().isEmpty())) {
                        // DB에서 가져왔는데 photos가 없으면 캐시나 API에서 photos만 가져오기
                        PlaceDetails cachedDetails = cacheService.getPlaceDetails(placeId, PlaceDetails.class);
                        if (cachedDetails != null && cachedDetails.getPhotos() != null && !cachedDetails.getPhotos().isEmpty()) {
                            details.setPhotos(cachedDetails.getPhotos());
                            details.setPhotoReferences(cachedDetails.getPhotoReferences());
                        } else {
                            // 캐시에도 없으면 API에서 photos만 가져오기
                            PlaceDetails apiDetails = googlePlacesClient.getPlaceDetails(placeId);
                            if (apiDetails != null) {
                                details.setPhotos(apiDetails.getPhotos());
                                details.setPhotoReferences(apiDetails.getPhotoReferences());
                                // 캐시 업데이트
                                cacheService.setPlaceDetails(placeId, details);
                            }
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

                        // 조회수 및 증가량 가져오기
                        Long viewCount = getViewCount(placeId);
                        Long viewCountIncrease = get10MinIncrease(placeId);

                        // PlaceResponse로 변환 (사진 URL 생성 포함)
                        return convertToPlaceResponse(details, walkTimeMinutes, viewCount, viewCountIncrease);
                    }
                    return null;
                } catch (Exception e) {
                    System.err.println("Error processing place " + placeId + ": " + e.getMessage());
                    e.printStackTrace();
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
     * @return 업데이트된 조회수
     */
    @Transactional
    public Long incrementViewCount(String placeId) {
        Optional<PlaceView> placeViewOpt = placeViewRepository.findByPlaceId(placeId);
        
        PlaceView placeView;
        if (placeViewOpt.isPresent()) {
            placeView = placeViewOpt.get();
            placeView.incrementViewCount();
        } else {
            placeView = new PlaceView();
            placeView.setPlaceId(placeId);
            placeView.setViewCount(1L);
            placeView.setLast10MinViewCount(0L); // 초기값 설정
            // 스냅샷은 처음 생성 시 현재 조회수로 설정
            placeView.update10MinSnapshot();
        }
        
        placeViewRepository.save(placeView);
        return placeView.getViewCount();
    }

    /**
     * 조회수 가져오기
     */
    private Long getViewCount(String placeId) {
        Optional<PlaceView> placeViewOpt = placeViewRepository.findByPlaceId(placeId);
        return placeViewOpt.map(PlaceView::getViewCount).orElse(0L);
    }

    /**
     * 최근 10분 동안의 조회수 증가량 가져오기
     */
    private Long get10MinIncrease(String placeId) {
        Optional<PlaceView> placeViewOpt = placeViewRepository.findByPlaceId(placeId);
        if (placeViewOpt.isPresent()) {
            PlaceView placeView = placeViewOpt.get();
            // 스냅샷이 없거나 10분이 지났으면 현재 조회수를 스냅샷으로 설정
            if (placeView.getLast10MinSnapshotAt() == null || 
                placeView.getLast10MinSnapshotAt().isBefore(java.time.LocalDateTime.now().minusMinutes(10))) {
                placeView.update10MinSnapshot();
                placeViewRepository.save(placeView);
            }
            return placeView.get10MinIncrease();
        }
        return 0L;
    }

    /**
     * 10분마다 모든 PlaceView의 스냅샷 업데이트 (스케줄러)
     */
    @Scheduled(fixedRate = 600000) // 10분 = 600,000ms
    @Transactional
    public void update10MinSnapshots() {
        List<PlaceView> allPlaceViews = placeViewRepository.findAll();
        for (PlaceView placeView : allPlaceViews) {
            placeView.update10MinSnapshot();
        }
        placeViewRepository.saveAll(allPlaceViews);
        System.out.println("Updated 10-minute snapshots for " + allPlaceViews.size() + " places");
    }

    /**
     * PlaceDetails를 PlaceResponse로 변환
     */
    private PlaceResponse convertToPlaceResponse(PlaceDetails details, int walkTimeMinutes, Long viewCount, Long viewCountIncrease) {
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
                .viewCountIncrease(viewCountIncrease != null ? viewCountIncrease : 0L)
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

    /**
     * PlaceDetails를 Store 엔티티로 변환하여 DB에 저장
     * 중복 저장 방지: placeId를 기준으로 이미 존재하면 저장하지 않음
     */
    private void saveStoreToDatabase(PlaceDetails details) {
        if (details == null || details.getPlaceId() == null || details.getPlaceId().isEmpty()) {
            return;
        }

        try {
            // 이미 존재하는지 확인 (동시성 문제 방지를 위해 먼저 체크)
            Optional<Store> existingStore = storeRepository.findByPlaceId(details.getPlaceId());
            
            if (existingStore.isPresent()) {
                // 이미 존재하는 경우 저장하지 않음
                System.out.println("Store already exists in database: " + details.getName() + " (" + details.getPlaceId() + ")");
                return;
            }

            // 새 Store 엔티티 생성
            Store store = new Store();
            store.setPlaceId(details.getPlaceId());
            store.setName(details.getName());
            
            // 타입 결정
            String type = determinePlaceType(details.getTypes());
            store.setType(type);
            
            store.setLatitude(details.getLatitude());
            store.setLongitude(details.getLongitude());
            store.setAddress(details.getAddress());
            
            // 가격 수준 변환 (Google: 0-4 -> 우리: 1-3)
            Integer priceLevel = convertPriceLevelToInteger(details.getPriceLevel());
            store.setPriceLevel(priceLevel);
            
            // CES 이유 생성 (LLM 또는 기본값)
            String cesReason = generateCesReason(details);
            store.setCesReason(cesReason);

            // DB에 저장
            storeRepository.save(store);
            System.out.println("Saved store to database: " + store.getName() + " (" + store.getPlaceId() + ")");
        } catch (DataIntegrityViolationException e) {
            // Unique constraint 위반 (동시에 여러 요청이 들어온 경우)
            // 이미 다른 요청에서 저장되었으므로 무시
            System.out.println("Store already exists (unique constraint): " + details.getPlaceId() + " - " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error saving store to database: " + details.getPlaceId() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Store 엔티티를 PlaceDetails로 변환 (DB에서 가져온 데이터를 API 응답 형식으로 변환)
     */
    private PlaceDetails convertStoreToPlaceDetails(Store store) {
        PlaceDetails details = new PlaceDetails();
        details.setPlaceId(store.getPlaceId());
        details.setName(store.getName());
        details.setLatitude(store.getLatitude());
        details.setLongitude(store.getLongitude());
        details.setAddress(store.getAddress());
        
        // 가격 수준 변환 (우리: 1-3 -> Google: 0-4)
        Integer googlePriceLevel = convertIntegerToGooglePriceLevel(store.getPriceLevel());
        details.setPriceLevel(googlePriceLevel);
        
        // 타입을 types 리스트로 변환
        List<String> types = new ArrayList<>();
        types.add(store.getType());
        details.setTypes(types);
        
        // DB에 저장된 정보만 있으므로 나머지는 null 또는 기본값
        details.setOneLineSummary(store.getCesReason());
        
        return details;
    }

    /**
     * Google 가격 수준(0-4)을 우리 형식(1-3)으로 변환
     */
    private Integer convertPriceLevelToInteger(Integer googlePriceLevel) {
        if (googlePriceLevel == null) {
            return 2; // 기본값
        }
        // Google: 0=무료, 1=저렴, 2=보통, 3=비쌈, 4=매우비쌈
        // 우리: 1=저렴, 2=보통, 3=비쌈
        if (googlePriceLevel <= 1) {
            return 1;
        } else if (googlePriceLevel == 2) {
            return 2;
        } else {
            return 3;
        }
    }

    /**
     * 우리 형식(1-3)을 Google 가격 수준(0-4)으로 변환
     */
    private Integer convertIntegerToGooglePriceLevel(Integer ourPriceLevel) {
        if (ourPriceLevel == null) {
            return 2; // 기본값
        }
        // 우리: 1=저렴, 2=보통, 3=비쌈
        // Google: 0=무료, 1=저렴, 2=보통, 3=비쌈, 4=매우비쌈
        if (ourPriceLevel == 1) {
            return 1;
        } else if (ourPriceLevel == 2) {
            return 2;
        } else {
            return 3;
        }
    }

    /**
     * CES 이유 생성 (LLM 또는 기본값)
     */
    private String generateCesReason(PlaceDetails details) {
        try {
            // 리뷰 텍스트 추출
            String reviewsText = null;
            if (details.getReviews() != null && !details.getReviews().isEmpty()) {
                reviewsText = details.getReviews().stream()
                        .map(review -> review.getText())
                        .filter(text -> text != null && !text.isEmpty())
                        .limit(3)
                        .collect(Collectors.joining(" "));
            }

            // 타입 결정
            String type = determinePlaceType(details.getTypes());
            
            // LLM으로 CES 이유 생성
            return llmService.generateCesReason(
                    details.getName(),
                    type,
                    reviewsText,
                    details.getOneLineSummary()
            );
        } catch (Exception e) {
            System.err.println("Error generating CES reason: " + e.getMessage());
            // 기본값 반환
            String type = determinePlaceType(details.getTypes());
            return generateFallbackCesReason(type);
        }
    }

    /**
     * 기본 CES 이유 생성
     */
    private String generateFallbackCesReason(String type) {
        switch (type) {
            case "fastfood":
                return "빠른 식사와 휴식에 완벽한 장소";
            case "cafe":
                return "회의나 작업하기 좋은 분위기";
            case "bar":
                return "CES 후 네트워킹과 휴식에 최적";
            case "restaurant":
                return "CES 참가자들이 자주 찾는 인기 레스토랑";
            default:
                return "CES 참가자들이 자주 찾는 인기 장소";
        }
    }
}

