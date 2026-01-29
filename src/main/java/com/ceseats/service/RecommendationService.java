package com.ceseats.service;

import com.ceseats.dto.RecommendationRequest;
import com.ceseats.dto.RecommendationResponse;
import com.ceseats.dto.StoreResponse;
import com.ceseats.entity.Store;
import com.ceseats.repository.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 기존 API 호환성을 위한 RecommendationService
 * 새로운 PlaceService를 사용하여 Google Places API에서 데이터를 가져옴
 */
@Slf4j
@Service
public class RecommendationService {

    private static final Logger logger = LoggerFactory.getLogger(RecommendationService.class);

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private ReviewService reviewService;

    public RecommendationResponse getRecommendations(RecommendationRequest request) {

        // 거리만 기준으로 DB에서 반경 내 모든 장소 조회
        double radiusKm = 50.0; // 기본 반경 50km
        List<Store> stores = storeRepository.findStoresWithinRadius(
                request.getLatitude(),
                request.getLongitude(),
                radiusKm
        );

        logger.info("[RecommendationService] findStoresWithinRadius returned {} stores", stores.size());

        // Store -> StoreResponse 변환
        List<StoreResponse> responses = new ArrayList<>();
        for (Store store : stores) {
            StoreResponse response = convertStoreToStoreResponse(store);
            responses.add(response);
        }

        return new RecommendationResponse(responses);
    }

    /**
     * Store 엔티티를 Basic 추천용 StoreResponse로 변환
     * (DB에 저장된 최소 정보 + Redis types만 사용)
     */
    private StoreResponse convertStoreToStoreResponse(Store store) {
        // Redis에서 types 조회
        List<String> types = reviewService.getTypes(store.getPlaceId());

        // 대표 type 결정
        String type = determineType(types);

        // 기본값 설정 (Basic 추천이므로 심플하게)
        Integer walkingTime = 0; // 계산 안 함
        Integer estimatedDuration = 30; // 기본 30분
        Integer priceLevel = 2; // 기본 $$ 수준

        StoreResponse response = new StoreResponse();
        response.setId(store.getPlaceId());
        response.setName(store.getName());
        response.setType(type);
        response.setWalkingTime(walkingTime);
        response.setEstimatedDuration(estimatedDuration);
        response.setPriceLevel(priceLevel);
        response.setCesReason(store.getReview());
        response.setLatitude(store.getLatitude());
        response.setLongitude(store.getLongitude());
        response.setAddress(store.getAddress());
        response.setPhotos(Collections.emptyList());
        response.setTypes(types);
        response.setReviews(Collections.emptyList());
        response.setViewCount(0);
        response.setViewCountIncrease(0L);

        logger.info("[RecommendationService] convertStoreToStoreResponse - placeId: {}, type: {}, types: {}",
                response.getId(), response.getType(), response.getTypes());

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
    private String determineType(List<String> types) {
        if (types == null || types.isEmpty()) return "other";

        if (types.contains("restaurant")) return "restaurant";
        if (types.contains("cafe") || types.contains("coffee_shop")) return "cafe";
        if (types.contains("fast_food_restaurant") || types.contains("meal_takeaway") || types.contains("fast_food"))
            return "fastfood";
        if (types.contains("bar") || types.contains("night_club")) return "bar";

        return "other";
    }
}

