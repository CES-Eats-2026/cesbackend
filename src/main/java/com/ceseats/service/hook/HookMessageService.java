package com.ceseats.service.hook;

import com.ceseats.service.google.PlaceDetails;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Hook 메시지 생성 서비스
 * 가격 비교를 기반으로 마케팅 스타일 메시지 생성
 */
@Service
public class HookMessageService {

    /**
     * Hook 메시지 생성
     * 주변 장소들의 평균 가격과 비교하여 메시지 생성
     */
    public String generateHookMessage(PlaceDetails targetPlace, List<PlaceDetails> nearbyPlaces) {
        if (targetPlace == null || targetPlace.getPriceLevel() == null) {
            return null;
        }

        // 주변 장소들의 평균 가격 수준 계산
        double averagePriceLevel = calculateAveragePriceLevel(nearbyPlaces);
        int targetPriceLevel = targetPlace.getPriceLevel();

        // 가격 차이 계산 (Google API는 0-4, 우리는 1-5로 변환하여 계산)
        double priceDifference = averagePriceLevel - (targetPriceLevel + 1);

        // Hook 메시지 생성
        if (priceDifference > 0.5) {
            // 타겟 장소가 주변보다 저렴함
            double percentage = (priceDifference / (targetPriceLevel + 1)) * 100;
            return generateCheaperMessage(targetPlace, percentage);
        } else if (priceDifference < -0.5) {
            // 타겟 장소가 주변보다 비쌈
            double percentage = Math.abs((priceDifference / (targetPriceLevel + 1)) * 100);
            return generatePremiumMessage(targetPlace, percentage);
        } else {
            // 비슷한 가격대
            return generateSimilarPriceMessage(targetPlace);
        }
    }

    /**
     * 주변 장소들의 평균 가격 수준 계산
     */
    private double calculateAveragePriceLevel(List<PlaceDetails> nearbyPlaces) {
        if (nearbyPlaces == null || nearbyPlaces.isEmpty()) {
            return 3.0; // 기본값 (중간 가격대)
        }

        double sum = 0.0;
        int count = 0;

        for (PlaceDetails place : nearbyPlaces) {
            if (place.getPriceLevel() != null) {
                // Google API는 0-4, 우리는 1-5로 변환
                sum += (place.getPriceLevel() + 1);
                count++;
            }
        }

        return count > 0 ? sum / count : 3.0;
    }

    /**
     * 저렴한 가격 메시지 생성
     */
    private String generateCheaperMessage(PlaceDetails place, double percentage) {
        String itemName = getItemName(place);
        int roundedPercentage = (int) Math.round(percentage);
        
        if (roundedPercentage >= 30) {
            return String.format("여기는 %s가 주변보다 %d%% 저렴해요! 가성비 최고예요.", 
                    itemName, roundedPercentage);
        } else if (roundedPercentage >= 15) {
            return String.format("여기는 %s가 주변보다 %d%% 저렴해요!", 
                    itemName, roundedPercentage);
        } else {
            return String.format("여기는 %s가 주변보다 조금 저렴해요.", itemName);
        }
    }

    /**
     * 프리미엄 가격 메시지 생성
     */
    private String generatePremiumMessage(PlaceDetails place, double percentage) {
        String itemName = getItemName(place);
        int roundedPercentage = (int) Math.round(percentage);
        
        if (roundedPercentage >= 30) {
            return String.format("여기는 %s가 주변보다 %d%% 비싸지만, 퀄리티가 확실해요.", 
                    itemName, roundedPercentage);
        } else {
            return String.format("여기는 %s가 주변보다 조금 비싸지만, 가치가 있어요.", itemName);
        }
    }

    /**
     * 비슷한 가격대 메시지 생성
     */
    private String generateSimilarPriceMessage(PlaceDetails place) {
        String itemName = getItemName(place);
        
        // 평점 기반 메시지
        if (place.getRating() != null && place.getRating() >= 4.5) {
            return String.format("여기는 %s가 주변 평균 가격이지만, 평점이 높아요!", itemName);
        } else if (place.getReviewCount() != null && place.getReviewCount() > 100) {
            return String.format("여기는 %s가 주변 평균 가격이고, 리뷰가 많아요!", itemName);
        } else {
            return String.format("여기는 %s가 주변 평균 가격대예요.", itemName);
        }
    }

    /**
     * 장소 타입에 따른 아이템 이름 반환
     */
    private String getItemName(PlaceDetails place) {
        // 장소 이름에서 추론하거나, 기본값 사용
        String name = place.getName() != null ? place.getName().toLowerCase() : "";
        
        if (name.contains("coffee") || name.contains("cafe") || name.contains("카페")) {
            return "아메리카노";
        } else if (name.contains("burger") || name.contains("버거")) {
            return "버거";
        } else if (name.contains("pizza") || name.contains("피자")) {
            return "피자";
        } else if (name.contains("sushi") || name.contains("초밥")) {
            return "초밥";
        } else {
            return "메인 메뉴";
        }
    }
}

