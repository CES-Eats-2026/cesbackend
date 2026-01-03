package com.ceseats.service;

import com.ceseats.dto.RecommendationRequest;
import com.ceseats.dto.RecommendationResponse;
import com.ceseats.dto.StoreResponse;
import com.ceseats.dto.request.PlaceSearchRequest;
import com.ceseats.dto.response.PlaceResponse;
import com.ceseats.dto.response.PlaceSearchResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 기존 API 호환성을 위한 RecommendationService
 * 새로운 PlaceService를 사용하여 Google Places API에서 데이터를 가져옴
 */
@Service
public class RecommendationService {

    @Autowired
    private PlaceService placeService;

    public RecommendationResponse getRecommendations(RecommendationRequest request) {
        // timeOption을 반경(미터)으로 변환
        // 도보 속도 5km/h 기준: timeOption 분 = (timeOption / 60) * 5km = (timeOption / 60) * 5000m
        // 더 정확하게는: timeOption 분 동안 걸을 수 있는 거리 = (timeOption / 60) * 5000
        int timeOptionMinutes = Integer.parseInt(request.getTimeOption());
        // 거리 필터링을 제거하기 위해 매우 큰 반경 사용 (Google Places API 최대값: 50000m)
        int radiusMeters = 50000; // 50km - 모든 장소를 가져오기 위해 최대값 사용

        // PlaceSearchRequest 생성
        PlaceSearchRequest placeRequest = new PlaceSearchRequest();
        placeRequest.setLatitude(request.getLatitude());
        placeRequest.setLongitude(request.getLongitude());
        placeRequest.setRadius(radiusMeters);
        placeRequest.setSortBy("price_asc"); // 기본 정렬

        // PlaceService를 사용하여 장소 검색
        PlaceSearchResponse placeResponse = placeService.searchPlaces(
                placeRequest,
                request.getLatitude(),
                request.getLongitude()
        );

        // PlaceResponse를 StoreResponse로 변환
        // 모든 장소를 가져와서 유형에 할당 (타입 필터링은 프론트엔드에서 수행)
        List<StoreResponse> storeResponses = placeResponse.getPlaces().stream()
                // 거리 필터링 주석 처리 - 원 외부의 핀도 표시하기 위해
                // .filter(place -> {
                //     // timeOption 필터링만 수행 (도보 시간이 timeOption 이하인 것만)
                //     // 타입 필터링은 프론트엔드에서 수행하여 모든 장소가 유형별로 표시되도록 함
                //     return place.getWalkTimeMinutes() != null && place.getWalkTimeMinutes() <= timeOptionMinutes;
                // })
                .map(this::convertToStoreResponse)
                .collect(Collectors.toList());

        return new RecommendationResponse(storeResponses);
    }

    /**
     * PlaceResponse를 StoreResponse로 변환
     */
    private StoreResponse convertToStoreResponse(PlaceResponse place) {
        // priceLevel 변환: "$", "$$", "$$$" -> 1, 2, 3
        Integer priceLevel = 2; // 기본값
        if (place.getPriceLevel() != null) {
            switch (place.getPriceLevel()) {
                case "$":
                    priceLevel = 1;
                    break;
                case "$$":
                    priceLevel = 2;
                    break;
                case "$$$":
                    priceLevel = 3;
                    break;
            }
        }

        // 타입은 PlaceResponse에서 이미 결정됨 (없으면 "other")
        String type = (place.getType() != null && !place.getType().isEmpty()) ? place.getType() : "other";

        // estimatedDuration 계산 (타입 기반)
        int estimatedDuration = estimateDuration(type, place.getWalkTimeMinutes() != null ? place.getWalkTimeMinutes() : 30);

        // 리뷰 변환
        List<StoreResponse.ReviewDto> reviewDtos = null;
        if (place.getReviews() != null && !place.getReviews().isEmpty()) {
            reviewDtos = place.getReviews().stream()
                    .map(review -> new StoreResponse.ReviewDto(
                            review.getAuthorName(),
                            review.getRating(),
                            review.getText(),
                            review.getTime(),
                            review.getRelativeTimeDescription()
                    ))
                    .collect(java.util.stream.Collectors.toList());
        }

        StoreResponse response = new StoreResponse(
                place.getId(),
                place.getName(),
                type,
                place.getWalkTimeMinutes(),
                estimatedDuration,
                priceLevel,
                place.getHookMessage() != null ? place.getHookMessage() : place.getOneLineSummary(),
                place.getLatitude(),
                place.getLongitude(),
                place.getAddress(),
                place.getPhotos(), // 사진 URL 리스트
                place.getTypes(), // Google Places API types 리스트
                reviewDtos, // 리뷰 리스트
                place.getViewCount() != null ? place.getViewCount().intValue() : 0, // 조회수
                place.getViewCountIncrease() != null ? place.getViewCountIncrease() : 0L // 최근 10분 증가량
        );
        return response;
    }

    private int estimateDuration(String type, int maxTime) {
        // 타입별 예상 소요 시간 추정
        switch (type) {
            case "fastfood":
                return Math.min(20, maxTime);
            case "cafe":
                return Math.min(30, maxTime);
            case "restaurant":
                return Math.min(60, maxTime);
            case "bar":
                return Math.min(90, maxTime);
            default:
                return Math.min(45, maxTime);
        }
    }
}

