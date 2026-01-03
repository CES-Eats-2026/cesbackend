package com.ceseats.controller;

import com.ceseats.dto.request.PlaceDataRequest;
import com.ceseats.dto.request.SearchNearbyRequest;
import com.ceseats.service.PlaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 장소 데이터 미리 수집을 위한 컨트롤러
 * Google Places API v1을 사용하여 배치 작업으로 사용
 */
@RestController
@RequestMapping("/api/prefetch")
public class PlacePrefetchController {

    @Autowired
    private PlaceService placeService;

    /**
     * 특정 위치 주변의 장소 데이터를 미리 수집하여 DB에 저장
     * Google Places API v1 searchNearby 사용
     * @param request SearchNearbyRequest (includedTypes, maxResultCount, locationRestriction)
     * @return 수집된 장소 수
     */
    @PostMapping("/places")
    public ResponseEntity<Map<String, Object>> prefetchPlaces(@RequestBody SearchNearbyRequest request) {
        try {
            if (request.getLocationRestriction() == null || 
                request.getLocationRestriction().getCircle() == null ||
                request.getLocationRestriction().getCircle().getCenter() == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "locationRestriction.circle.center is required");
                return ResponseEntity.badRequest().body(response);
            }

            double latitude = request.getLocationRestriction().getCircle().getCenter().getLatitude();
            double longitude = request.getLocationRestriction().getCircle().getCenter().getLongitude();
            double radius = request.getLocationRestriction().getCircle().getRadius();
            // includedTypes가 null이면 기본값, 빈 배열이면 모든 타입 반환
            List<String> includedTypes = request.getIncludedTypes();
            if (includedTypes == null) {
                includedTypes = List.of("restaurant"); // 기본값
            }
            // 빈 배열이면 그대로 전달 (모든 타입 반환)
            int maxResultCount = request.getMaxResultCount() != null ? request.getMaxResultCount() : 10;

            System.out.println("=== Starting prefetch request ===");
            System.out.println("Location: (" + latitude + ", " + longitude + ")");
            System.out.println("Radius: " + radius + "m");
            System.out.println("Types: " + includedTypes);
            System.out.println("Max results: " + maxResultCount);
            
            placeService.prefetchAndStorePlaces(latitude, longitude, radius, includedTypes, maxResultCount);
            
            System.out.println("=== Prefetch request completed ===");
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Places prefetched and stored successfully");
            response.put("latitude", latitude);
            response.put("longitude", longitude);
            response.put("radius", radius);
            response.put("includedTypes", includedTypes);
            response.put("maxResultCount", maxResultCount);
            
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            // Google Places API v1 관련 에러 처리
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            
            if (e.getMessage() != null) {
                if (e.getMessage().contains("Places API (New) is not enabled")) {
                    response.put("error", "Places API (New) is not enabled");
                    response.put("message", "Please enable Places API (New) in Google Cloud Console");
                    response.put("url", "https://console.cloud.google.com/apis/api/places.googleapis.com/overview");
                    response.put("details", e.getMessage());
                    return ResponseEntity.status(403).body(response);
                } else if (e.getMessage().contains("billing")) {
                    response.put("error", "Google Places API v1 requires billing to be enabled");
                    response.put("message", "Please enable billing in Google Cloud Console");
                    response.put("url", "https://console.cloud.google.com/billing");
                    response.put("details", e.getMessage());
                    return ResponseEntity.status(402).body(response); // 402 Payment Required
                }
            }
            
            response.put("error", e.getMessage());
            response.put("details", e.getCause() != null ? e.getCause().getMessage() : null);
            return ResponseEntity.internalServerError().body(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * JSON 형식의 장소 데이터를 받아서 PostgreSQL stores 테이블과 Redis에 저장
     * @param placeData JSON에서 추출한 장소 데이터
     * @return 저장 결과
     */
    @PostMapping("/places/from-json")
    public ResponseEntity<Map<String, Object>> savePlaceFromJson(@RequestBody PlaceDataRequest placeData) {
        try {
            if (placeData == null || placeData.getId() == null || placeData.getId().isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "place_id (id) is required");
                return ResponseEntity.badRequest().body(response);
            }

            placeService.savePlaceDataFromJson(placeData);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Place data saved successfully");
            response.put("placeId", placeData.getId());
            response.put("name", placeData.getDisplayName() != null ? placeData.getDisplayName() : placeData.getId());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 여러 장소 데이터를 한 번에 저장
     * @param placeDataList 장소 데이터 리스트
     * @return 저장 결과
     */
    @PostMapping("/places/batch")
    public ResponseEntity<Map<String, Object>> savePlacesBatch(@RequestBody List<PlaceDataRequest> placeDataList) {
        try {
            if (placeDataList == null || placeDataList.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "placeDataList is required");
                return ResponseEntity.badRequest().body(response);
            }

            int successCount = 0;
            int failCount = 0;
            
            for (PlaceDataRequest placeData : placeDataList) {
                try {
                    placeService.savePlaceDataFromJson(placeData);
                    successCount++;
                } catch (Exception e) {
                    System.err.println("Error saving place: " + (placeData.getId() != null ? placeData.getId() : "unknown") + " - " + e.getMessage());
                    failCount++;
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Batch save completed");
            response.put("total", placeDataList.size());
            response.put("successCount", successCount);
            response.put("failCount", failCount);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}

