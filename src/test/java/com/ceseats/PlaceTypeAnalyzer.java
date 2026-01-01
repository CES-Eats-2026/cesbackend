package com.ceseats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

/**
 * Google Places API를 사용하여 현재 위치 주변의 장소 타입 분포를 분석하는 유틸리티
 */
public class PlaceTypeAnalyzer {
    
    private static final String PLACES_API_BASE_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json";
    private static final String API_KEY = "AIzaSyCV4oBI-Pig1YPxIMb7XBD4p4X7kv-M6LQ"; // 실제 API 키로 변경
    
    // Las Vegas CES 전시장 위치 (The Venetian Expo)
    private static final double LATITUDE = 36.1147;
    private static final double LONGITUDE = -115.1728;
    private static final int RADIUS = 2000; // 2km
    
    // 테스트할 타입들
    private static final String[] TYPES_TO_TEST = {
        "restaurant",
        "cafe",
        "meal_takeaway",
        "bar",
        "bakery",
        "food",
        "meal_delivery",
        "night_club",
        "liquor_store",
        "store"
    };
    
    public static void main(String[] args) {
        RestTemplate restTemplate = new RestTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        
        System.out.println("=== Google Places API 타입 분포 분석 ===\n");
        System.out.println("위치: Las Vegas (36.1147, -115.1728)");
        System.out.println("반경: " + RADIUS + "m\n");
        System.out.println("=".repeat(80) + "\n");
        
        Map<String, Integer> typeCounts = new HashMap<>();
        Map<String, List<String>> typePlaces = new HashMap<>();
        
        // 각 타입별로 검색
        for (String type : TYPES_TO_TEST) {
            try {
                System.out.println("검색 중: " + type + "...");
                
                UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(PLACES_API_BASE_URL)
                        .queryParam("location", LATITUDE + "," + LONGITUDE)
                        .queryParam("radius", RADIUS)
                        .queryParam("type", type)
                        .queryParam("key", API_KEY);
                
                String response = restTemplate.getForObject(uriBuilder.toUriString(), String.class);
                JsonNode root = objectMapper.readTree(response);
                
                if (root.has("results") && root.get("results").isArray()) {
                    int count = root.get("results").size();
                    typeCounts.put(type, count);
                    
                    List<String> places = new ArrayList<>();
                    for (JsonNode result : root.get("results")) {
                        String name = result.has("name") ? result.get("name").asText() : "Unknown";
                        places.add(name);
                    }
                    typePlaces.put(type, places);
                    
                    System.out.println("  ✓ " + type + ": " + count + "개 발견");
                } else {
                    System.out.println("  ✗ " + type + ": 결과 없음");
                    typeCounts.put(type, 0);
                }
                
                // API 호출 제한을 피하기 위해 잠시 대기
                Thread.sleep(200);
                
            } catch (Exception e) {
                System.err.println("  ✗ " + type + " 검색 실패: " + e.getMessage());
                typeCounts.put(type, 0);
            }
        }
        
        // 결과 요약
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== 타입별 장소 수 요약 ===\n");
        
        typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> {
                    System.out.printf("%-20s: %3d개\n", entry.getKey(), entry.getValue());
                });
        
        // 상위 3개 타입의 장소 목록
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== 상위 타입별 장소 목록 ===\n");
        
        typeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> {
                    String type = entry.getKey();
                    int count = entry.getValue();
                    List<String> places = typePlaces.get(type);
                    
                    System.out.println(type + " (" + count + "개):");
                    if (places != null && !places.isEmpty()) {
                        places.stream().limit(10).forEach(place -> 
                            System.out.println("  - " + place)
                        );
                        if (places.size() > 10) {
                            System.out.println("  ... 외 " + (places.size() - 10) + "개");
                        }
                    }
                    System.out.println();
                });
        
        // 추천 타입 조합
        System.out.println("=".repeat(80));
        System.out.println("=== 추천 타입 조합 ===\n");
        
        int totalRestaurant = typeCounts.getOrDefault("restaurant", 0);
        int totalCafe = typeCounts.getOrDefault("cafe", 0);
        int totalMealTakeaway = typeCounts.getOrDefault("meal_takeaway", 0);
        int totalBar = typeCounts.getOrDefault("bar", 0);
        int totalFood = typeCounts.getOrDefault("food", 0);
        
        System.out.println("현재 사용 중인 타입:");
        System.out.println("  - restaurant: " + totalRestaurant + "개");
        System.out.println("  - cafe: " + totalCafe + "개");
        System.out.println("  - meal_takeaway: " + totalMealTakeaway + "개");
        System.out.println("  - bar: " + totalBar + "개");
        System.out.println("  - 총합: " + (totalRestaurant + totalCafe + totalMealTakeaway + totalBar) + "개");
        
        System.out.println("\n추가 고려할 타입:");
        if (totalFood > 0) {
            System.out.println("  - food: " + totalFood + "개 (일반 음식점)");
        }
        if (typeCounts.getOrDefault("bakery", 0) > 0) {
            System.out.println("  - bakery: " + typeCounts.get("bakery") + "개 (베이커리)");
        }
        if (typeCounts.getOrDefault("meal_delivery", 0) > 0) {
            System.out.println("  - meal_delivery: " + typeCounts.get("meal_delivery") + "개 (배달 음식)");
        }
    }
}

