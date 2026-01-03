package com.ceseats.service;

import com.ceseats.dto.request.PlaceDataRequest;
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
import com.ceseats.service.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 장소 검색 및 추천 메인 서비스
 */
@Service
public class PlaceService {

    private static final Logger logger = LoggerFactory.getLogger(PlaceService.class);

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

    @Autowired
    private ReviewService reviewService;
    
    // 병렬 처리를 위한 스레드 풀 (최대 10개 동시 요청)
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double WALKING_SPEED_KMH = 5.0; // 도보 속도 5km/h

    /**
     * 장소 검색
     * Google API 호출 최소화: DB에서 먼저 조회, 없을 때만 API 호출
     */
    public PlaceSearchResponse searchPlaces(PlaceSearchRequest request, double userLatitude, double userLongitude) {
        // 반경을 km로 변환 (미터 -> km)
        double radiusKm = request.getRadius() / 1000.0;
        
        // 1. DB에서 원형 거리 내의 장소들을 먼저 조회 (Google API 호출 최소화)
        List<Store> storesInRadius = storeRepository.findStoresWithinRadius(
                request.getLatitude(),
                request.getLongitude(),
                radiusKm
        );

        // 2. DB에 있는 장소들을 PlaceResponse로 변환
        List<CompletableFuture<PlaceResponse>> futures = new ArrayList<>();
        Set<String> foundPlaceIds = new HashSet<>();
        
        for (Store store : storesInRadius) {
            foundPlaceIds.add(store.getPlaceId());
            CompletableFuture<PlaceResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    PlaceDetails details = convertStoreToPlaceDetails(store);
                    
                    // Redis에서 reviews 가져오기
                    List<Map<String, Object>> reviews = reviewService.getReviews(store.getPlaceId());
                    if (reviews != null && !reviews.isEmpty()) {
                        // Redis에서 가져온 reviews를 PlaceDetails 형식으로 변환
                        List<PlaceDetails.Review> reviewList = reviews.stream()
                                .map(reviewMap -> {
                                    PlaceDetails.Review review = new PlaceDetails.Review();
                                    review.setAuthorName((String) reviewMap.get("authorName"));
                                    review.setRating((Integer) reviewMap.get("rating"));
                                    review.setText((String) reviewMap.get("text"));
                                    review.setTime(((Number) reviewMap.get("time")).longValue());
                                    review.setRelativeTimeDescription((String) reviewMap.get("relativeTimeDescription"));
                                    return review;
                                })
                                .collect(Collectors.toList());
                        details.setReviews(reviewList);
                    }

                    // Redis에서 types 가져오기
                    List<String> types = reviewService.getTypes(store.getPlaceId());
                    logger.info("[PlaceService] getTypes from Redis - placeId: {}, storeName: {}, types: {}, typesSize: {}", 
                               store.getPlaceId(), store.getName(), types, types != null ? types.size() : 0);
                    if (types != null && !types.isEmpty()) {
                        details.setTypes(types);
                        logger.info("[PlaceService] types set to PlaceDetails - placeId: {}, types: {}", store.getPlaceId(), types);
                    } else {
                        logger.warn("[PlaceService] WARNING: types is null or empty for placeId: {}", store.getPlaceId());
                    }

                    // 도보 시간 계산
                    int walkTimeMinutes = calculateWalkTime(
                            userLatitude,
                            userLongitude,
                            details.getLatitude(),
                            details.getLongitude()
                    );

                    // 조회수 및 증가량 가져오기
                    Long viewCount = getViewCount(store.getPlaceId());
                    Long viewCountIncrease = get10MinIncrease(store.getPlaceId());

                    return convertToPlaceResponse(details, walkTimeMinutes, viewCount, viewCountIncrease);
                } catch (Exception e) {
                    System.err.println("Error processing store " + store.getPlaceId() + ": " + e.getMessage());
                    e.printStackTrace();
                    return null;
                }
            }, executorService);
            futures.add(future);
        }

        // 3. Google API 호출 최소화: DB에 있는 장소만 사용
        // 필요시 DB에 장소가 너무 적을 때만 Google API로 보완하는 로직 추가 가능

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
        
        logger.info("[PlaceService] convertToPlaceResponse - placeId: {}, name: {}, types: {}, typesSize: {}, determinedType: {}", 
                   details.getPlaceId(), details.getName(), details.getTypes(), 
                   details.getTypes() != null ? details.getTypes().size() : 0, type);
        
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
                .types(details.getTypes()) // Google Places API types 리스트
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
            
            store.setLatitude(details.getLatitude());
            store.setLongitude(details.getLongitude());
            
            // 가격 수준을 문자열 형식으로 변환 (예: "$10 ~ $20")
            String priceLevelStr = convertPriceLevelToString(details.getPriceLevel());
            store.setPriceLevel(priceLevelStr);
            
            // 한줄평 (reason) 생성
            String reason = generateCesReason(details);
            // reason이 null이면 기본값 사용 (데이터베이스 not-null 제약조건 위반 방지)
            if (reason == null || reason.isEmpty()) {
                String type = determinePlaceType(details.getTypes());
                reason = generateFallbackCesReason(type);
            }
            if (reason == null || reason.isEmpty()) {
                reason = "CES 참가자들이 자주 찾는 인기 장소";
            }
            store.setReason(reason);
            
            // address 저장
            if (details.getAddress() != null && !details.getAddress().isEmpty()) {
                store.setAddress(details.getAddress());
            }
            
            // reviews를 Redis에 저장 (최대 5개)
            if (details.getReviews() != null && !details.getReviews().isEmpty()) {
                List<Map<String, Object>> reviewsList = details.getReviews().stream()
                        .limit(5) // 최대 5개만 저장
                        .map(review -> {
                            Map<String, Object> reviewMap = new java.util.HashMap<>();
                            reviewMap.put("authorName", review.getAuthorName() != null ? review.getAuthorName() : "");
                            reviewMap.put("rating", review.getRating() != null ? review.getRating() : 0);
                            reviewMap.put("text", review.getText() != null ? review.getText() : "");
                            reviewMap.put("time", review.getTime() != null ? review.getTime() : 0L);
                            reviewMap.put("relativeTimeDescription", review.getRelativeTimeDescription() != null ? review.getRelativeTimeDescription() : "");
                            return reviewMap;
                        })
                        .filter(reviewMap -> {
                            // text가 비어있지 않은 리뷰만 저장
                            String text = (String) reviewMap.get("text");
                            return text != null && !text.isEmpty();
                        })
                        .collect(Collectors.toList());
                
                if (!reviewsList.isEmpty()) {
                    reviewService.setReviews(details.getPlaceId(), reviewsList);
                } else {
                    System.out.println("No valid reviews to save for: " + details.getPlaceId() + " (all reviews have empty text)");
                }
            } else {
                System.out.println("No reviews to save for: " + details.getPlaceId() + " (reviews is null or empty)");
            }

            // types를 Redis에 저장
            if (details.getTypes() != null && !details.getTypes().isEmpty()) {
                reviewService.setTypes(details.getPlaceId(), details.getTypes());
            } else {
                System.out.println("No types to save for: " + details.getPlaceId() + " (types is null or empty)");
            }

            // DB에 저장
            storeRepository.save(store);
            System.out.println("✅ Saved store to PostgreSQL: " + store.getName() + " (" + store.getPlaceId() + ")");
            System.out.println("   - Address: " + (store.getAddress() != null ? store.getAddress() : "null"));
            System.out.println("   - Reason: " + (store.getReason() != null ? store.getReason().substring(0, Math.min(50, store.getReason().length())) + "..." : "null"));
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
        
        // 가격 수준 변환 (문자열 -> Integer)
        Integer googlePriceLevel = convertStringToGooglePriceLevel(store.getPriceLevel());
        details.setPriceLevel(googlePriceLevel);
        
        // DB에 저장된 정보만 있으므로 나머지는 null 또는 기본값
        details.setOneLineSummary(store.getReason());
        
        // Redis에서 reviews 가져오기
        List<Map<String, Object>> reviewsFromRedis = reviewService.getReviews(store.getPlaceId());
        if (reviewsFromRedis != null && !reviewsFromRedis.isEmpty()) {
            // Redis에서 가져온 reviews를 PlaceDetails의 Review 형식으로 변환
            List<PlaceDetails.Review> reviewList = reviewsFromRedis.stream()
                    .map(reviewMap -> {
                        PlaceDetails.Review review = new PlaceDetails.Review();
                        review.setAuthorName((String) reviewMap.get("authorName"));
                        review.setRating((Integer) reviewMap.get("rating"));
                        review.setText((String) reviewMap.get("text"));
                        review.setTime(((Number) reviewMap.get("time")).longValue());
                        review.setRelativeTimeDescription((String) reviewMap.get("relativeTimeDescription"));
                        return review;
                    })
                    .collect(Collectors.toList());
            details.setReviews(reviewList);
        }

        // Redis에서 types 가져오기
        List<String> typesFromRedis = reviewService.getTypes(store.getPlaceId());
        logger.info("[PlaceService] convertStoreToPlaceDetails - placeId: {}, storeName: {}, typesFromRedis: {}, typesSize: {}", 
                   store.getPlaceId(), store.getName(), typesFromRedis, typesFromRedis != null ? typesFromRedis.size() : 0);
        if (typesFromRedis != null && !typesFromRedis.isEmpty()) {
            details.setTypes(typesFromRedis);
            logger.info("[PlaceService] types set to PlaceDetails in convertStoreToPlaceDetails - placeId: {}, types: {}", 
                       store.getPlaceId(), typesFromRedis);
        } else {
            logger.warn("[PlaceService] WARNING: typesFromRedis is null or empty in convertStoreToPlaceDetails - placeId: {}", 
                       store.getPlaceId());
        }
        
        return details;
    }

    /**
     * Google 가격 수준(0-4)을 문자열 형식("$10 ~ $20")으로 변환
     * 가공된 price_level을 PostgreSQL에 저장
     */
    private String convertPriceLevelToString(Integer googlePriceLevel) {
        if (googlePriceLevel == null) {
            return "$15 ~ $25"; // 기본값
        }
        // Google API: 0=무료, 1=저렴, 2=보통, 3=비쌈, 4=매우비쌈
        // 가공된 형식으로 변환하여 PostgreSQL에 저장
        switch (googlePriceLevel) {
            case 0:
                return "$0 ~ $10"; // 무료 또는 매우 저렴
            case 1:
                return "$10 ~ $20"; // 저렴
            case 2:
                return "$20 ~ $40"; // 보통
            case 3:
                return "$40 ~ $60"; // 비쌈
            case 4:
                return "$60 ~ $100"; // 매우 비쌈
            default:
                return "$15 ~ $25"; // 기본값
        }
    }

    /**
     * 문자열 형식("$10 ~ $20")을 Google 가격 수준(0-4)으로 변환
     */
    private Integer convertStringToGooglePriceLevel(String priceLevelStr) {
        if (priceLevelStr == null || priceLevelStr.isEmpty()) {
            return 2; // 기본값
        }
        // 간단한 파싱: "$10 ~ $20" 형식에서 첫 번째 숫자 추출
        if (priceLevelStr.contains("$10") || priceLevelStr.contains("$15")) {
            return 1;
        } else if (priceLevelStr.contains("$20")) {
            return 2;
        } else if (priceLevelStr.contains("$40")) {
            return 3;
        } else if (priceLevelStr.contains("$60")) {
            return 4;
        }
        return 2; // 기본값
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

    /**
     * Google Places API v1을 사용하여 장소 데이터를 미리 수집하고 DB에 저장
     * 배치 작업으로 사용 가능
     */
    public void prefetchAndStorePlaces(double latitude, double longitude, double radiusMeters, List<String> includedTypes, int maxResultCount) {
        try {
            // Google Places API v1 호출 (Place Details API 호출 불필요 - searchNearby 응답에 모든 정보 포함)
            List<com.ceseats.dto.response.SearchNearbyResponse.Place> places = googlePlacesClient.searchNearbyV1(
                    latitude,
                    longitude,
                    radiusMeters,
                    includedTypes,
                    maxResultCount
            );

            System.out.println("Found " + places.size() + " places from Google Places API v1");

            int savedCount = 0;
            int skippedCount = 0;
            int errorCount = 0;

            // 각 Place 객체를 PlaceDataRequest로 변환하여 DB 저장
            for (com.ceseats.dto.response.SearchNearbyResponse.Place place : places) {
                try {
                    String placeId = place.getId();
                    if (placeId == null || placeId.isEmpty()) {
                        System.err.println("Place ID is null or empty, skipping");
                        errorCount++;
                        continue;
                    }
                    
                    // 이미 DB에 있는지 확인
                    Optional<Store> existingStore = storeRepository.findByPlaceId(placeId);
                    if (existingStore.isPresent()) {
                        System.out.println("Skipping existing place: " + placeId);
                        skippedCount++;
                        continue; // 이미 있으면 스킵
                    }

                    // SearchNearbyResponse.Place를 PlaceDataRequest로 변환
                    PlaceDataRequest placeData = convertSearchNearbyPlaceToPlaceDataRequest(place);
                    if (placeData != null && placeData.getLocation() != null) {
                        // DB에 저장 (가공된 price_level 포함, reviews와 types는 Redis에 저장)
                        savePlaceDataFromJson(placeData);
                        savedCount++;
                        System.out.println("Successfully saved place: " + placeId);
                    } else {
                        if (placeData == null) {
                            System.err.println("Failed to convert place to PlaceDataRequest (location missing): " + placeId);
                        } else {
                            System.err.println("Failed to convert place to PlaceDataRequest (location is null): " + placeId);
                        }
                        errorCount++;
                    }
                } catch (Exception e) {
                    System.err.println("Error processing place " + place.getId() + ": " + e.getMessage());
                    e.printStackTrace();
                    errorCount++;
                }
            }
            
            System.out.println("Prefetch summary - Saved: " + savedCount + ", Skipped: " + skippedCount + ", Errors: " + errorCount);
        } catch (Exception e) {
            System.err.println("Error prefetching places: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 기존 메서드 호환성 유지 (기본값 사용)
     */
    public void prefetchAndStorePlaces(double latitude, double longitude, int radiusMeters) {
        prefetchAndStorePlaces(latitude, longitude, (double) radiusMeters, List.of("restaurant", "cafe", "bar", "meal_takeaway"), 20);
    }

    /**
     * JSON에서 받은 PlaceDataRequest를 파싱하여 PostgreSQL stores 테이블과 Redis에 저장
     * @param placeData JSON에서 추출한 장소 데이터
     */
    public void savePlaceDataFromJson(PlaceDataRequest placeData) {
        if (placeData == null || placeData.getId() == null || placeData.getId().isEmpty()) {
            System.err.println("Invalid place data: place_id is required");
            return;
        }

        String placeId = placeData.getId();
        
        try {
            // 이미 존재하는지 확인
            Optional<Store> existingStore = storeRepository.findByPlaceId(placeId);
            
            if (existingStore.isPresent()) {
                System.out.println("Store already exists in database: " + placeId);
                // 기존 데이터 업데이트는 필요시 구현
                return;
            }

            // 새 Store 엔티티 생성
            Store store = new Store();
            store.setPlaceId(placeId);
            
            // name 추출: name 필드에서 "places/" 접두사 제거하거나 displayName 사용
            String storeName = extractStoreName(placeData);
            store.setName(storeName);
            
            // location 추출
            if (placeData.getLocation() != null) {
                store.setLatitude(placeData.getLocation().getLatitude());
                store.setLongitude(placeData.getLocation().getLongitude());
            } else {
                System.err.println("Location is required for place: " + placeId);
                return;
            }
            
            // price_level 추출 및 변환
            // 우선순위: priceLevelString (enum) > priceLevel (Integer)
            String priceLevelStr = null;
            if (placeData.getPriceLevelString() != null && !placeData.getPriceLevelString().isEmpty()) {
                priceLevelStr = convertPriceLevelEnumToString(placeData.getPriceLevelString());
            } else if (placeData.getPriceLevel() != null) {
                priceLevelStr = convertPriceLevelToString(placeData.getPriceLevel());
            }
            if (priceLevelStr == null) {
                priceLevelStr = "$15 ~ $25"; // 기본값
            }
            store.setPriceLevel(priceLevelStr);
            
            // address 추출 및 저장
            if (placeData.getFormattedAddress() != null && !placeData.getFormattedAddress().isEmpty()) {
                store.setAddress(placeData.getFormattedAddress());
                System.out.println("Setting address for place " + placeId + ": " + placeData.getFormattedAddress());
            } else {
                System.out.println("WARNING: No address for place: " + placeId + " (formattedAddress is null or empty)");
            }
            
            // reason (한줄평) 생성 - reviewSummary 우선, 없으면 editorialSummary/reviews 사용
            String reason = generateCesReasonFromJson(placeData);
            // reason이 null이거나 비어있으면 기본값 사용 (reviewSummary가 없어도 문제없음)
            if (reason == null || reason.isEmpty()) {
                String type = determinePlaceTypeFromTypes(placeData.getTypes());
                reason = generateFallbackCesReason(type);
                System.out.println("Using fallback reason for place: " + placeId + " (reviewSummary not available)");
            }
            // reason이 여전히 null이면 최종 fallback 사용 (데이터베이스 not-null 제약조건 위반 방지)
            if (reason == null || reason.isEmpty()) {
                reason = "CES 참가자들이 자주 찾는 인기 장소";
                System.err.println("WARNING: Reason is still null for place: " + placeId + ", using final fallback");
            }
            store.setReason(reason);
            System.out.println("Setting reason for place " + placeId + ": " + (reason != null ? reason.substring(0, Math.min(50, reason.length())) + "..." : "null"));
            
            // types를 Redis에 저장 (DB 저장 전에 먼저 저장)
            if (placeData.getTypes() != null && !placeData.getTypes().isEmpty()) {
                System.out.println("Attempting to save " + placeData.getTypes().size() + " types to Redis for place: " + placeId);
                System.out.println("Types list: " + placeData.getTypes());
                
                // reviewService가 null인지 확인
                if (reviewService == null) {
                    System.err.println("ERROR: reviewService is null! Cannot save types to Redis.");
                } else {
                    try {
                        reviewService.setTypes(placeId, placeData.getTypes());
                        System.out.println("Types saved to Redis for place: " + placeId);
                    } catch (Exception e) {
                        System.err.println("ERROR: Exception while saving types to Redis for place: " + placeId);
                        e.printStackTrace();
                    }
                }
            } else {
                System.out.println("No types to save for place: " + placeId + " (types is null or empty)");
            }
            
            // reviews를 Redis에 저장 (최대 5개, DB 저장 전에 먼저 저장)
            if (placeData.getReviews() != null && !placeData.getReviews().isEmpty()) {
                System.out.println("=== Processing reviews for place: " + placeId + " ===");
                System.out.println("Total reviews received: " + placeData.getReviews().size());
                
                List<Map<String, Object>> reviewsList = placeData.getReviews().stream()
                        .limit(5) // 최대 5개만 저장
                        .map(review -> {
                            Map<String, Object> reviewMap = new java.util.HashMap<>();
                            
                            // 디버깅: 첫 번째 리뷰의 구조 확인
                            if (placeData.getReviews().indexOf(review) == 0) {
                                System.out.println("Sample review structure:");
                                System.out.println("  - name: " + review.getName());
                                System.out.println("  - rating: " + review.getRating());
                                System.out.println("  - text: " + (review.getText() != null ? (review.getText().getText() != null ? review.getText().getText().substring(0, Math.min(50, review.getText().getText().length())) + "..." : "null") : "null"));
                                System.out.println("  - authorAttribution: " + (review.getAuthorAttribution() != null ? review.getAuthorAttribution().getDisplayName() : "null"));
                                System.out.println("  - publishTime: " + review.getPublishTime());
                                System.out.println("  - relativePublishTimeDescription: " + review.getRelativePublishTimeDescription());
                            }
                            
                            // authorName 추출: authorAttribution.displayName 우선, 없으면 author (레거시)
                            String authorName = "";
                            if (review.getAuthorAttribution() != null && review.getAuthorAttribution().getDisplayName() != null) {
                                authorName = review.getAuthorAttribution().getDisplayName();
                            } else if (review.getAuthor() != null) {
                                authorName = review.getAuthor();
                            }
                            reviewMap.put("authorName", authorName);
                            
                            // rating 추출 (null 체크)
                            reviewMap.put("rating", review.getRating() != null ? review.getRating() : 0);
                            
                            // text 추출: text.text 우선, 없으면 레거시 text 필드
                            String reviewText = "";
                            if (review.getText() != null && review.getText().getText() != null) {
                                reviewText = review.getText().getText();
                            }
                            reviewMap.put("text", reviewText);
                            
                            // publishTime을 Unix timestamp로 변환
                            Long time = null;
                            if (review.getPublishTimeUnix() != null) {
                                time = review.getPublishTimeUnix();
                            } else if (review.getPublishTime() != null && !review.getPublishTime().isEmpty()) {
                                time = parseIso8601ToUnix(review.getPublishTime());
                            }
                            reviewMap.put("time", time);
                            
                            // relativeTimeDescription 추출
                            String relativeTime = review.getRelativePublishTimeDescription() != null ? 
                                                 review.getRelativePublishTimeDescription() : "";
                            reviewMap.put("relativeTimeDescription", relativeTime);
                            
                            return reviewMap;
                        })
                        .filter(reviewMap -> {
                            // text가 비어있지 않은 리뷰만 저장
                            String text = (String) reviewMap.get("text");
                            boolean isValid = text != null && !text.isEmpty();
                            if (!isValid) {
                                System.out.println("Filtering out review with empty text (author: " + reviewMap.get("authorName") + ")");
                            }
                            return isValid;
                        })
                        .collect(Collectors.toList());
                
                System.out.println("Valid reviews after filtering: " + reviewsList.size() + " out of " + Math.min(5, placeData.getReviews().size()));
                
                if (!reviewsList.isEmpty()) {
                    System.out.println("Saving " + reviewsList.size() + " reviews to Redis for place: " + placeId);
                    reviewService.setReviews(placeId, reviewsList);
                    System.out.println("Reviews saved to Redis for place: " + placeId);
                } else {
                    System.out.println("No valid reviews to save for place: " + placeId + " (all reviews have empty text)");
                }
            } else {
                System.out.println("No reviews to save for place: " + placeId + " (reviews is null or empty)");
            }
            
            // DB에 저장
            storeRepository.save(store);
            System.out.println("✅ Saved store to database: " + store.getName() + " (" + store.getPlaceId() + ")");
            System.out.println("   - Address: " + (store.getAddress() != null ? store.getAddress() : "null"));
            System.out.println("   - Reason: " + (store.getReason() != null ? store.getReason().substring(0, Math.min(50, store.getReason().length())) + "..." : "null"));
        } catch (DataIntegrityViolationException e) {
            System.out.println("Store already exists (unique constraint): " + placeId + " - " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error saving place data from JSON: " + placeId + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * PlaceDataRequest에서 store name 추출
     */
    private String extractStoreName(PlaceDataRequest placeData) {
        // displayName 객체의 text 우선 사용
        if (placeData.getDisplayName() != null && placeData.getDisplayName().getText() != null 
            && !placeData.getDisplayName().getText().isEmpty()) {
            return placeData.getDisplayName().getText();
        }
        
        // displayNameText (단순 String) 사용
        if (placeData.getDisplayNameText() != null && !placeData.getDisplayNameText().isEmpty()) {
            return placeData.getDisplayNameText();
        }
        
        // name 필드에서 "places/" 접두사 제거
        if (placeData.getName() != null && !placeData.getName().isEmpty()) {
            String name = placeData.getName();
            if (name.startsWith("places/")) {
                // "places/ChIJiyxvtBdawokRHeEVToh8Te4" 형식이면 id 사용
                return placeData.getId();
            }
            return name;
        }
        
        // fallback: id 사용
        return placeData.getId();
    }

    /**
     * PlaceDataRequest에서 openNow 추출
     * 우선순위: regularOpeningHours.openNow > currentOpeningHours.openNow > openingHours.openNow > openNow
     */
    private Boolean extractOpenNow(PlaceDataRequest placeData) {
        if (placeData.getRegularOpeningHours() != null && placeData.getRegularOpeningHours().getOpenNow() != null) {
            return placeData.getRegularOpeningHours().getOpenNow();
        }
        if (placeData.getCurrentOpeningHours() != null && placeData.getCurrentOpeningHours().getOpenNow() != null) {
            return placeData.getCurrentOpeningHours().getOpenNow();
        }
        if (placeData.getOpeningHours() != null && placeData.getOpeningHours().getOpenNow() != null) {
            return placeData.getOpeningHours().getOpenNow();
        }
        if (placeData.getOpenNow() != null) {
            return placeData.getOpenNow();
        }
        return null;
    }

    /**
     * PlaceDataRequest에서 CES reason 생성
     * 우선순위: reviewSummary.text.text > editorialSummary > reviews > fallback
     * reviewSummary가 없어도 null이 허용되며, fallback 로직으로 처리됨
     */
    private String generateCesReasonFromJson(PlaceDataRequest placeData) {
        // 1. reviewSummary.text.text 우선 사용 (API 요청 시 제공되는 리뷰 요약)
        // reviewSummary가 없어도 정상적으로 다음 단계로 진행
        System.out.println("=== Checking reviewSummary for reason generation ===");
        System.out.println("reviewSummary is null: " + (placeData.getReviewSummary() == null));
        
        if (placeData.getReviewSummary() != null) {
            System.out.println("reviewSummary.text is null: " + (placeData.getReviewSummary().getText() == null));
            if (placeData.getReviewSummary().getText() != null) {
                System.out.println("reviewSummary.text.text is null: " + (placeData.getReviewSummary().getText().getText() == null));
                if (placeData.getReviewSummary().getText().getText() != null) {
                    System.out.println("reviewSummary.text.text is empty: " + placeData.getReviewSummary().getText().getText().isEmpty());
                }
            }
        }
        
        if (placeData.getReviewSummary() != null && 
            placeData.getReviewSummary().getText() != null &&
            placeData.getReviewSummary().getText().getText() != null &&
            !placeData.getReviewSummary().getText().getText().isEmpty()) {
            String reviewSummaryText = placeData.getReviewSummary().getText().getText();
            System.out.println("✅ Using reviewSummary.text.text for reason: " + reviewSummaryText.substring(0, Math.min(50, reviewSummaryText.length())) + "...");
            return reviewSummaryText;
        }
        
        // reviewSummary가 없거나 비어있으면 다음 단계로 진행 (정상 동작)
        System.out.println("⚠️ reviewSummary not available, trying editorialSummary...");
        
        // 2. editorialSummary 사용
        String description = "";
        if (placeData.getEditorialSummary() != null) {
            // text 필드 우선 사용
            if (placeData.getEditorialSummary().getText() != null && !placeData.getEditorialSummary().getText().isEmpty()) {
                description = placeData.getEditorialSummary().getText();
                System.out.println("✅ Using editorialSummary.text for reason: " + description.substring(0, Math.min(50, description.length())) + "...");
                return description;
            } else if (placeData.getEditorialSummary().getOverview() != null) {
                description = placeData.getEditorialSummary().getOverview();
                System.out.println("✅ Using editorialSummary.overview for reason: " + description.substring(0, Math.min(50, description.length())) + "...");
                return description;
            }
        }
        System.out.println("⚠️ editorialSummary not available, trying reviews...");
        
        // 3. reviews 텍스트 수집
        String reviewsText = "";
        if (placeData.getReviews() != null && !placeData.getReviews().isEmpty()) {
            reviewsText = placeData.getReviews().stream()
                    .filter(r -> {
                        // text 객체의 text 필드 또는 레거시 text 필드 확인
                        if (r.getText() != null && r.getText().getText() != null && !r.getText().getText().isEmpty()) {
                            return true;
                        }
                        // 레거시: text가 단순 String인 경우 (이미 처리됨)
                        return false;
                    })
                    .map(r -> {
                        // text 객체의 text 필드 우선 사용
                        if (r.getText() != null && r.getText().getText() != null) {
                            return r.getText().getText();
                        }
                        // 레거시: 단순 String (이미 처리되지 않음)
                        return "";
                    })
                    .filter(text -> !text.isEmpty())
                    .limit(5) // 상위 5개만 사용
                    .collect(Collectors.joining(" "));
        }
        
        // 4. 타입 결정
        String type = determinePlaceTypeFromTypes(placeData.getTypes());
        
        // 5. LLM으로 CES 이유 생성 (editorialSummary나 reviews가 있는 경우)
        String storeName = extractStoreName(placeData);
        if (llmService != null && (!reviewsText.isEmpty() || !description.isEmpty())) {
            try {
                String llmReason = llmService.generateCesReason(storeName, type, reviewsText, description);
                // LLM이 null을 반환하지 않도록 보장
                if (llmReason != null && !llmReason.isEmpty()) {
                    return llmReason;
                }
            } catch (Exception e) {
                System.err.println("Error generating CES reason with LLM: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // 6. Fallback: 기본 이유 생성 (항상 null이 아닌 값 반환)
        String fallbackReason = generateFallbackCesReason(type);
        return fallbackReason != null ? fallbackReason : "CES 참가자들이 자주 찾는 인기 장소";
    }

    /**
     * types 리스트에서 장소 타입 결정
     */
    private String determinePlaceTypeFromTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return "restaurant";
        }
        
        for (String type : types) {
            if (type.equals("restaurant") || type.equals("food") || type.equals("meal_takeaway")) {
                return "restaurant";
            } else if (type.equals("cafe") || type.equals("bakery")) {
                return "cafe";
            } else if (type.equals("bar") || type.equals("night_club")) {
                return "bar";
            } else if (type.contains("fast") || type.equals("meal_delivery")) {
                return "fastfood";
            }
        }
        return "restaurant";
    }

    /**
     * ISO 8601 형식의 날짜 문자열을 Unix timestamp로 변환
     */
    private Long parseIso8601ToUnix(String iso8601) {
        try {
            // ISO 8601 형식: "2024-01-15T10:30:00Z" 또는 "2024-01-15T10:30:00+00:00"
            java.time.Instant instant = java.time.Instant.parse(iso8601);
            return instant.getEpochSecond();
        } catch (Exception e) {
            System.err.println("Error parsing ISO 8601 date: " + iso8601 + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Google Places API v1의 priceLevel enum을 문자열 형식으로 변환
     * "PRICE_LEVEL_FREE", "PRICE_LEVEL_INEXPENSIVE", "PRICE_LEVEL_MODERATE", 
     * "PRICE_LEVEL_EXPENSIVE", "PRICE_LEVEL_VERY_EXPENSIVE"
     */
    private String convertPriceLevelEnumToString(String priceLevelEnum) {
        if (priceLevelEnum == null || priceLevelEnum.isEmpty()) {
            return "$15 ~ $25"; // 기본값
        }
        
        switch (priceLevelEnum) {
            case "PRICE_LEVEL_FREE":
                return "$0 ~ $10";
            case "PRICE_LEVEL_INEXPENSIVE":
                return "$10 ~ $20";
            case "PRICE_LEVEL_MODERATE":
                return "$20 ~ $40";
            case "PRICE_LEVEL_EXPENSIVE":
                return "$40 ~ $60";
            case "PRICE_LEVEL_VERY_EXPENSIVE":
                return "$60 ~ $100";
            default:
                return "$15 ~ $25"; // 기본값
        }
    }
    
    /**
     * SearchNearbyResponse.Place를 PlaceDataRequest로 변환
     * Place Details API 호출 없이 searchNearby 응답만으로 저장하기 위함
     */
    private PlaceDataRequest convertSearchNearbyPlaceToPlaceDataRequest(com.ceseats.dto.response.SearchNearbyResponse.Place place) {
        if (place == null || place.getId() == null) {
            return null;
        }
        
        PlaceDataRequest placeData = new PlaceDataRequest();
        placeData.setId(place.getId());
        
        // displayName
        if (place.getDisplayName() != null) {
            PlaceDataRequest.DisplayName displayName = new PlaceDataRequest.DisplayName();
            displayName.setText(place.getDisplayName().getText());
            displayName.setLanguageCode(place.getDisplayName().getLanguageCode());
            placeData.setDisplayName(displayName);
        }
        
        // location
        if (place.getLocation() != null) {
            // Google Places API v1 응답: location은 직접 { latitude, longitude } 구조
            Double lat = place.getLocation().getLatitude();
            Double lng = place.getLocation().getLongitude();
            
            if (lat != null && lng != null) {
                PlaceDataRequest.Location location = new PlaceDataRequest.Location();
                location.setLatitude(lat);
                location.setLongitude(lng);
                placeData.setLocation(location);
                System.out.println("Location found for " + place.getId() + ": " + lat + ", " + lng);
            } else {
                // location이 있지만 latitude/longitude가 null인 경우
                System.err.println("Warning: Location latitude/longitude is missing for place: " + place.getId() + " (" + 
                    (place.getDisplayName() != null ? place.getDisplayName().getText() : "unknown") + ")");
                System.err.println("  - place.getLocation().getLatitude(): " + lat);
                System.err.println("  - place.getLocation().getLongitude(): " + lng);
                // location이 필수이므로 null 반환하여 저장하지 않음
                return null;
            }
        } else {
            // location이 없으면 로그 출력하고 null 반환 (저장 불가)
            System.err.println("Warning: Location is missing for place: " + place.getId() + " (" + 
                (place.getDisplayName() != null ? place.getDisplayName().getText() : "unknown") + ")");
            // location이 필수이므로 null 반환하여 저장하지 않음
            return null;
        }
        
        // types
        placeData.setTypes(place.getTypes());
        
        // priceLevel
        placeData.setPriceLevel(place.getPriceLevel());
        
        // rating
        placeData.setRating(place.getRating());
        placeData.setUserRatingCount(place.getUserRatingCount());
        
        // formattedAddress
        placeData.setFormattedAddress(place.getFormattedAddress());
        
        // phone numbers
        placeData.setNationalPhoneNumber(place.getNationalPhoneNumber());
        placeData.setInternationalPhoneNumber(place.getInternationalPhoneNumber());
        
        // URIs
        placeData.setWebsiteUri(place.getWebsiteUri());
        placeData.setGoogleMapsUri(place.getGoogleMapsUri());
        
        // editorialSummary
        if (place.getEditorialSummary() != null) {
            PlaceDataRequest.EditorialSummary editorialSummary = new PlaceDataRequest.EditorialSummary();
            editorialSummary.setText(place.getEditorialSummary().getText());
            editorialSummary.setOverview(place.getEditorialSummary().getOverview());
            editorialSummary.setLanguageCode(place.getEditorialSummary().getLanguageCode());
            placeData.setEditorialSummary(editorialSummary);
        }
        
        // reviewSummary
        if (place.getReviewSummary() != null && place.getReviewSummary().getText() != null) {
            PlaceDataRequest.ReviewSummary reviewSummary = new PlaceDataRequest.ReviewSummary();
            PlaceDataRequest.ReviewSummary.TextContent textContent = new PlaceDataRequest.ReviewSummary.TextContent();
            textContent.setText(place.getReviewSummary().getText().getText());
            textContent.setLanguageCode(place.getReviewSummary().getText().getLanguageCode());
            reviewSummary.setText(textContent);
            placeData.setReviewSummary(reviewSummary);
            System.out.println("✅ reviewSummary found for " + place.getId() + ": " + 
                (textContent.getText() != null ? textContent.getText().substring(0, Math.min(50, textContent.getText().length())) + "..." : "null"));
        } else {
            System.out.println("⚠️ reviewSummary not found in API response for " + place.getId() + 
                " (place.getReviewSummary() is " + (place.getReviewSummary() == null ? "null" : "not null") + ")");
        }
        
        // reviews
        if (place.getReviews() != null && !place.getReviews().isEmpty()) {
            List<PlaceDataRequest.Review> reviews = place.getReviews().stream()
                    .map(review -> {
                        PlaceDataRequest.Review reviewDto = new PlaceDataRequest.Review();
                        reviewDto.setName(review.getName());
                        reviewDto.setRelativePublishTimeDescription(review.getRelativePublishTimeDescription());
                        reviewDto.setRating(review.getRating());
                        
                        if (review.getText() != null) {
                            PlaceDataRequest.Review.TextContent textContent = new PlaceDataRequest.Review.TextContent();
                            textContent.setText(review.getText().getText());
                            textContent.setLanguageCode(review.getText().getLanguageCode());
                            reviewDto.setText(textContent);
                        }
                        
                        if (review.getOriginalText() != null) {
                            PlaceDataRequest.Review.TextContent originalTextContent = new PlaceDataRequest.Review.TextContent();
                            originalTextContent.setText(review.getOriginalText().getText());
                            originalTextContent.setLanguageCode(review.getOriginalText().getLanguageCode());
                            reviewDto.setOriginalText(originalTextContent);
                        }
                        
                        reviewDto.setPublishTime(review.getPublishTime());
                        reviewDto.setPublishTimeUnix(review.getPublishTimeUnix());
                        
                        if (review.getAuthorAttribution() != null) {
                            PlaceDataRequest.Review.AuthorAttribution authorAttribution = new PlaceDataRequest.Review.AuthorAttribution();
                            authorAttribution.setDisplayName(review.getAuthorAttribution().getDisplayName());
                            authorAttribution.setUri(review.getAuthorAttribution().getUri());
                            authorAttribution.setPhotoUri(review.getAuthorAttribution().getPhotoUri());
                            reviewDto.setAuthorAttribution(authorAttribution);
                        }
                        
                        return reviewDto;
                    })
                    .collect(Collectors.toList());
            placeData.setReviews(reviews);
        }
        
        // regularOpeningHours
        if (place.getRegularOpeningHours() != null) {
            PlaceDataRequest.RegularOpeningHours regularOpeningHours = new PlaceDataRequest.RegularOpeningHours();
            regularOpeningHours.setOpenNow(place.getRegularOpeningHours().getOpenNow());
            regularOpeningHours.setWeekdayDescriptions(place.getRegularOpeningHours().getWeekdayDescriptions());
            regularOpeningHours.setNextOpenTime(place.getRegularOpeningHours().getNextOpenTime());
            
            if (place.getRegularOpeningHours().getPeriods() != null) {
                List<PlaceDataRequest.RegularOpeningHours.Period> periods = place.getRegularOpeningHours().getPeriods().stream()
                        .map(period -> {
                            PlaceDataRequest.RegularOpeningHours.Period periodDto = new PlaceDataRequest.RegularOpeningHours.Period();
                            
                            if (period.getOpen() != null) {
                                PlaceDataRequest.RegularOpeningHours.Period.DayTime open = new PlaceDataRequest.RegularOpeningHours.Period.DayTime();
                                open.setDay(period.getOpen().getDay());
                                open.setHour(period.getOpen().getHour());
                                open.setMinute(period.getOpen().getMinute());
                                periodDto.setOpen(open);
                            }
                            
                            if (period.getClose() != null) {
                                PlaceDataRequest.RegularOpeningHours.Period.DayTime close = new PlaceDataRequest.RegularOpeningHours.Period.DayTime();
                                close.setDay(period.getClose().getDay());
                                close.setHour(period.getClose().getHour());
                                close.setMinute(period.getClose().getMinute());
                                periodDto.setClose(close);
                            }
                            
                            return periodDto;
                        })
                        .collect(Collectors.toList());
                regularOpeningHours.setPeriods(periods);
            }
            
            placeData.setRegularOpeningHours(regularOpeningHours);
        }
        
        // currentOpeningHours
        if (place.getCurrentOpeningHours() != null) {
            PlaceDataRequest.CurrentOpeningHours currentOpeningHours = new PlaceDataRequest.CurrentOpeningHours();
            currentOpeningHours.setOpenNow(place.getCurrentOpeningHours().getOpenNow());
            currentOpeningHours.setWeekdayDescriptions(place.getCurrentOpeningHours().getWeekdayDescriptions());
            currentOpeningHours.setNextOpenTime(place.getCurrentOpeningHours().getNextOpenTime());
            
            if (place.getCurrentOpeningHours().getPeriods() != null) {
                List<PlaceDataRequest.CurrentOpeningHours.Period> periods = place.getCurrentOpeningHours().getPeriods().stream()
                        .map(period -> {
                            PlaceDataRequest.CurrentOpeningHours.Period periodDto = new PlaceDataRequest.CurrentOpeningHours.Period();
                            
                            if (period.getOpen() != null) {
                                PlaceDataRequest.CurrentOpeningHours.Period.DayTime open = new PlaceDataRequest.CurrentOpeningHours.Period.DayTime();
                                open.setDay(period.getOpen().getDay());
                                open.setHour(period.getOpen().getHour());
                                open.setMinute(period.getOpen().getMinute());
                                periodDto.setOpen(open);
                            }
                            
                            if (period.getClose() != null) {
                                PlaceDataRequest.CurrentOpeningHours.Period.DayTime close = new PlaceDataRequest.CurrentOpeningHours.Period.DayTime();
                                close.setDay(period.getClose().getDay());
                                close.setHour(period.getClose().getHour());
                                close.setMinute(period.getClose().getMinute());
                                periodDto.setClose(close);
                            }
                            
                            return periodDto;
                        })
                        .collect(Collectors.toList());
                currentOpeningHours.setPeriods(periods);
            }
            
            placeData.setCurrentOpeningHours(currentOpeningHours);
        }
        
        return placeData;
    }
}

