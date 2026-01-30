package com.ceseats.service;

import com.ceseats.dto.request.PlaceDataRequest;
import com.ceseats.dto.request.PlaceSearchRequest;
import com.ceseats.dto.response.PlaceResponse;
import com.ceseats.dto.response.PlaceSearchResponse;
import com.ceseats.entity.PlaceView;
import com.ceseats.entity.Store;
import com.ceseats.repository.PlaceViewRepository;
import com.ceseats.repository.StoreRepository;
import com.ceseats.service.cache.CacheService;
import com.ceseats.service.google.GooglePlacesClient;
import com.ceseats.service.google.PlaceDetails;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    @Autowired
    private ReviewService reviewService;
    
    // 병렬 처리를 위한 스레드 풀 (최대 10개 동시 요청)
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double WALKING_SPEED_KMH = 5.0; //도보 속도 5km/h

    /**
     * 장소 검색
     * Google API 호출 최소화: DB에서 먼저 조회, 없을 때만 API 호출
     */
    public PlaceSearchResponse searchPlaces(PlaceSearchRequest request, double userLatitude, double userLongitude) {
        
        //반경을 km로 변환 (미터 -> km)
        double radiusKm = request.getRadius() / 1000.0;
        
        //1. DB에서 원형 거리 내의 장소들을 먼저 조회 (Google API 호출 최소화)
        List<Store> storesInRadius = storeRepository.findStoresWithinRadius(
                request.getLatitude(),
                request.getLongitude(),
                radiusKm
        );
        

        //2. DB에 있는 장소들을 PlaceResponse로 변환
        List<CompletableFuture<PlaceResponse>> futures = new ArrayList<>();
        Set<String> foundPlaceIds = new HashSet<>();
        
        for (Store store : storesInRadius) {
            foundPlaceIds.add(store.getPlaceId());
            CompletableFuture<PlaceResponse> future = CompletableFuture.supplyAsync(() -> {
                try {
                    //reviews와 types를 Redis에서 가져와서 설정함
                    PlaceDetails details = convertStoreToPlaceDetails(store);

                    //도보 시간 계산
                    int walkTimeMinutes = calculateWalkTime(
                            userLatitude,
                            userLongitude,
                            details.getLatitude(),
                            details.getLongitude()
                    );

                    //조회수 및 증가량 가져오기
                    Long viewCount = getViewCount(store.getPlaceId());
                    Long viewCountIncrease = get10MinIncrease(store.getPlaceId());

                    return convertToPlaceResponse(details, walkTimeMinutes, viewCount, viewCountIncrease);
                } catch (Exception e) {
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
     * 동시 요청 시 duplicate key 가능하므로, INSERT 실패하면 재조회 후 UPDATE로 재시도
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
            placeView.setLast10MinViewCount(0L);
            placeView.update10MinSnapshot();
        }

        try {
            placeViewRepository.save(placeView);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 이미 같은 place_id가 INSERT된 경우: 재조회 후 증가
            if (e.getMessage() != null && e.getMessage().contains("place_views_place_id_key")) {
                placeViewOpt = placeViewRepository.findByPlaceId(placeId);
                if (placeViewOpt.isPresent()) {
                    placeView = placeViewOpt.get();
                    placeView.incrementViewCount();
                    placeViewRepository.save(placeView);
                    return placeView.getViewCount();
                }
                throw new IllegalStateException("place_views duplicate key but row not found for placeId: " + placeId, e);
            }
            throw e;
        }
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
        
        log.info("[PlaceService] convertToPlaceResponse - placeId: {}, name: {}, types: {}, typesSize: {}, determinedType: {}", 
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

            // 새 Store 엔티티 생성 (간소화된 필드만 사용)
            Store store = new Store();
            store.setPlaceId(details.getPlaceId());
            store.setName(details.getName());

            store.setLatitude(details.getLatitude());
            store.setLongitude(details.getLongitude());

            // address 저장 (옵셔널)
            if (details.getAddress() != null && !details.getAddress().isEmpty()) {
                store.setAddress(details.getAddress());
            }

            // Google Map 링크 저장 (placeUri)
            String mapUrl = googlePlacesClient.generateGoogleMapUrl(details.getPlaceId());
            store.setLink(mapUrl);

            // 리뷰 요약 또는 CES reason을 review 컬럼에 저장 (숫자만 있으면 fallback 사용)
            String reviewSummary = generateCesReason(details);
            store.setReview(sanitizeReviewText(reviewSummary, details.getTypes()));
            
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
            System.out.println("   - Review: " + (store.getReview() != null ? store.getReview().substring(0, Math.min(50, store.getReview().length())) + "..." : "null"));
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
        log.info("[PlaceService] convertStoreToPlaceDetails START - placeId: {}, name: {}", store.getPlaceId(), store.getName());
        
        PlaceDetails details = new PlaceDetails();
        details.setPlaceId(store.getPlaceId());
        details.setName(store.getName());
        details.setLatitude(store.getLatitude());
        details.setLongitude(store.getLongitude());

        // 가격 수준은 더 이상 Store에 저장하지 않으므로 기본값 사용
        details.setPriceLevel(2); // 기본 가격 레벨

        // review 컬럼을 한 줄 요약으로 사용
        details.setOneLineSummary(store.getReview());
        
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
        log.info("[PlaceService] convertStoreToPlaceDetails - calling reviewService.getTypes for placeId: {}", store.getPlaceId());
        List<String> typesFromRedis = reviewService.getTypes(store.getPlaceId());
        log.info("[PlaceService] convertStoreToPlaceDetails - placeId: {}, storeName: {}, typesFromRedis: {}, typesSize: {}", 
                   store.getPlaceId(), store.getName(), typesFromRedis, typesFromRedis != null ? typesFromRedis.size() : 0);
        if (typesFromRedis != null && !typesFromRedis.isEmpty()) {
            details.setTypes(typesFromRedis);
            log.info("[PlaceService] convertStoreToPlaceDetails - types set to PlaceDetails - placeId: {}, types: {}", 
                       store.getPlaceId(), typesFromRedis);
        } else {
            log.warn("[PlaceService] convertStoreToPlaceDetails - WARNING: typesFromRedis is null or empty - placeId: {}", 
                       store.getPlaceId());
            // types가 null이면 빈 리스트로 설정하여 NPE 방지
            details.setTypes(new ArrayList<>());
        }
        
        log.info("[PlaceService] convertStoreToPlaceDetails END - placeId: {}, final types: {}", 
                   store.getPlaceId(), details.getTypes());
        
        return details;
    }


    /**
     * 리뷰 이유 생성 (LLM 또는 기본값)
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
     * 기본 CES 이유 생성 (Table A 타입별 한 줄 문구)
     */
    private String generateFallbackCesReason(String type) {
        if (type == null) return "CES 참가자들이 자주 찾는 인기 장소";
        switch (type) {
            case "fastfood":
                return "빠른 식사와 휴식에 완벽한 장소";
            case "cafe":
            case "bakery":
                return "회의나 작업하기 좋은 분위기";
            case "bar":
            case "night_club":
                return "CES 후 네트워킹과 휴식에 최적";
            case "restaurant":
                return "CES 참가자들이 자주 찾는 인기 레스토랑";
            case "shopping_mall":
            case "department_store":
            case "store":
                return "쇼핑과 여유를 즐기기 좋은 장소";
            case "supermarket":
            case "convenience_store":
                return "필요한 것을 바로 구하기 좋은 장소";
            case "park":
            case "tourist_attraction":
                return "산책과 휴식에 좋은 장소";
            case "museum":
            case "art_gallery":
                return "문화·전시를 즐기기 좋은 장소";
            case "lodging":
                return "CES 참가자들이 많이 이용하는 숙소";
            case "gym":
            case "spa":
                return "휴식과 스트레칭에 좋은 장소";
            case "library":
            case "university":
            case "school":
                return "조용히 일하거나 공부하기 좋은 장소";
            case "subway_station":
            case "train_station":
            case "bus_station":
            case "airport":
                return "이동 시 편리한 거점";
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
            //호출
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

                    //SearchNearbyResponse.Place를 PlaceDataRequest로 변환
                    PlaceDataRequest placeData = convertSearchNearbyPlaceToPlaceDataRequest(place);
                    if (placeData != null && placeData.getLocation() != null) {
                        //DB에 저장
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

            // 새 Store 엔티티 생성 (간소화된 필드만 사용)
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

            // formattedAddress → Store.address 저장 (없으면 null)
            store.setAddress(placeData.getFormattedAddress() != null && !placeData.getFormattedAddress().isEmpty()
                    ? placeData.getFormattedAddress() : null);
            if (store.getAddress() != null) {
                System.out.println("Setting address for place " + placeId + ": " + store.getAddress());
            } else {
                System.out.println("WARNING: No address for place: " + placeId + " (formattedAddress is null or empty)");
            }

            // Google Maps 링크 저장
            if (placeData.getGoogleMapsUri() != null && !placeData.getGoogleMapsUri().isEmpty()) {
                store.setLink(placeData.getGoogleMapsUri());
            }

            // generativeSummary.overview.text 또는 fallback을 review 컬럼에 저장 (숫자만 있으면 fallback 사용)
            String reviewText = generateCesReasonFromJson(placeData);
            store.setReview(sanitizeReviewText(reviewText, placeData.getTypes()));
            
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
            
            // DB에 저장
            storeRepository.save(store);
            System.out.println("✅ Saved store to database: " + store.getName() + " (" + store.getPlaceId() + ")");
            System.out.println("   - Address: " + (store.getAddress() != null ? store.getAddress() : "null"));
            System.out.println("   - Review: " + (store.getReview() != null ? store.getReview().substring(0, Math.min(50, store.getReview().length())) + "..." : "null"));
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
     * PlaceDataRequest에서 Store.review용 CES reason 생성
     * 우선순위: generativeSummary.overview.text > fallback (타입 기반)
     * 숫자만 있는 값은 사용하지 않고 fallback 사용
     */
    private String generateCesReasonFromJson(PlaceDataRequest placeData) {
        // 1) generativeSummary.overview.text
        if (placeData.getGenerativeSummary() != null
                && placeData.getGenerativeSummary().getOverview() != null
                && placeData.getGenerativeSummary().getOverview().getText() != null) {
            String overviewText = placeData.getGenerativeSummary().getOverview().getText().trim();
            if (!overviewText.isEmpty() && !isOnlyDigits(overviewText)) {
                return overviewText;
            }
        }
        // 2) fallback (타입 기반)
        String type = determinePlaceTypeFromTypes(placeData.getTypes());
        String fallbackReason = generateFallbackCesReason(type);
        return fallbackReason != null ? fallbackReason : "참가자들이 자주 찾는 인기 장소";
    }

    /** 숫자만으로 이루어진 문자열인지 확인 (리뷰 개수 등 오매핑 제외용) */
    private boolean isOnlyDigits(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** 공백·쉼표·마침표 제거 후 숫자만 있는지 확인 (예: "16 470", "16,470" 제외) */
    private boolean looksLikeNumber(String s) {
        if (s == null || s.isEmpty()) return true;
        String stripped = s.replace(" ", "").replace(",", "").replace(".", "").replace("_", "").trim();
        return isOnlyDigits(stripped);
    }

    /** review 텍스트가 숫자/숫자형이면 타입 기반 fallback으로 대체 */
    private String sanitizeReviewText(String text, List<String> types) {
        if (text == null || text.trim().isEmpty()) {
            return fallbackReviewForTypes(types);
        }
        String trimmed = text.trim();
        if (looksLikeNumber(trimmed)) {
            return fallbackReviewForTypes(types);
        }
        return trimmed;
    }

    private String fallbackReviewForTypes(List<String> types) {
        String type = types != null && !types.isEmpty() ? determinePlaceTypeFromTypes(types) : "other";
        String fallback = generateFallbackCesReason(type);
        return fallback != null ? fallback : "참가자들이 자주 찾는 인기 장소";
    }

    /**
     * types 리스트에서 장소 타입 결정
     * Google Places API v1 Table A 타입(음식·쇼핑·관광·숙박·교통 등) 반영
     */
    private String determinePlaceTypeFromTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return "other";
        }
        for (String type : types) {
            // 음식·카페
            if (type.equals("restaurant") || type.equals("food") || type.equals("meal_takeaway")) return "restaurant";
            if (type.equals("cafe") || type.equals("bakery")) return "cafe";
            if (type.equals("bar") || type.equals("night_club")) return "bar";
            if (type.contains("fast") || type.equals("meal_delivery")) return "fastfood";
            // 쇼핑
            if (type.equals("shopping_mall")) return "shopping_mall";
            if (type.equals("supermarket") || type.equals("convenience_store")) return type;
            if (type.equals("store") || type.equals("department_store") || type.equals("clothing_store")
                    || type.equals("shoe_store") || type.equals("jewelry_store") || type.equals("electronics_store")
                    || type.equals("furniture_store") || type.equals("home_goods_store") || type.equals("hardware_store")
                    || type.equals("book_store") || type.equals("pet_store") || type.equals("liquor_store")) return type;
            // 관광·문화
            if (type.equals("tourist_attraction") || type.equals("park") || type.equals("museum")
                    || type.equals("art_gallery") || type.equals("casino")) return type;
            // 숙박·편의
            if (type.equals("lodging") || type.equals("spa") || type.equals("gym")) return type;
            if (type.equals("pharmacy") || type.equals("hospital") || type.equals("bank") || type.equals("atm")) return type;
            // 교통
            if (type.equals("gas_station") || type.equals("parking") || type.equals("subway_station")
                    || type.equals("train_station") || type.equals("bus_station") || type.equals("airport")) return type;
            // 종교·교육·기타
            if (type.equals("church") || type.equals("hindu_temple") || type.equals("mosque") || type.equals("synagogue")) return type;
            if (type.equals("school") || type.equals("university") || type.equals("library")) return type;
            if (type.equals("zoo") || type.equals("aquarium") || type.equals("amusement_park")) return type;
        }
        return types.get(0);
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
     * SearchNearbyResponse.Place를 PlaceDataRequest로 변환
     * Place Details API 호출 없이 searchNearby 응답만으로 저장하기 위함
     */
    private PlaceDataRequest convertSearchNearbyPlaceToPlaceDataRequest(com.ceseats.dto.response.SearchNearbyResponse.Place place) {
        if (place == null || place.getId() == null) {
            return null;
        }
        
        PlaceDataRequest placeData = new PlaceDataRequest();
        placeData.setId(place.getId());
        
        //displayName
        if (place.getDisplayName() != null) {
            PlaceDataRequest.DisplayName displayName = new PlaceDataRequest.DisplayName();
            displayName.setText(place.getDisplayName().getText());
            displayName.setLanguageCode(place.getDisplayName().getLanguageCode());
            placeData.setDisplayName(displayName);
        }
        
        //location
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
                //location이 있지만 latitude/longitude가 null인 경우
                System.err.println("Warning: Location latitude/longitude is missing for place: " + place.getId() + " (" + 
                    (place.getDisplayName() != null ? place.getDisplayName().getText() : "unknown") + ")");
                System.err.println("  - place.getLocation().getLatitude(): " + lat);
                System.err.println("  - place.getLocation().getLongitude(): " + lng);
                //location이 필수이므로 null 반환하여 저장하지 않음
                return null;
            }
        } else {
            //location이 없으면 로그 출력하고 null 반환 (저장 불가)
            System.err.println("Warning: Location is missing for place: " + place.getId() + " (" + 
                (place.getDisplayName() != null ? place.getDisplayName().getText() : "unknown") + ")");
            //location이 필수이므로 null 반환하여 저장하지 않음
            return null;
        }
        
        placeData.setTypes(place.getTypes());
        placeData.setFormattedAddress(place.getFormattedAddress());
        placeData.setGoogleMapsUri(place.getGoogleMapsUri());
        
        // generativeSummary.overview.text (review 우선 사용)
        if (place.getGenerativeSummary() != null && place.getGenerativeSummary().getOverview() != null) {
            String overviewText = place.getGenerativeSummary().getOverview().getText();
            if (overviewText != null && !overviewText.trim().isEmpty()) {
                PlaceDataRequest.GenerativeSummary gen = new PlaceDataRequest.GenerativeSummary();
                PlaceDataRequest.GenerativeSummary.Overview overview = new PlaceDataRequest.GenerativeSummary.Overview();
                overview.setText(overviewText.trim());
                gen.setOverview(overview);
                placeData.setGenerativeSummary(gen);
            }
        }
        return placeData;
    }
}

