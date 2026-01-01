package com.ceseats.controller;

import com.ceseats.dto.request.PlaceSearchRequest;
import com.ceseats.dto.response.PlaceSearchResponse;
import com.ceseats.service.PlaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 장소 검색 및 추천 REST API 컨트롤러
 */
@RestController
@RequestMapping("/api/places")
@CrossOrigin(origins = {"https://cesfront.vercel.app", "http://localhost:3000"})
public class PlaceController {

    @Autowired
    private PlaceService placeService;

    /**
     * 장소 검색 API (POST 방식)
     * POST /api/places/search
     * RequestBody로 복잡한 검색 조건을 받을 수 있음
     */
    @PostMapping("/search")
    public ResponseEntity<PlaceSearchResponse> searchPlaces(
            @RequestBody PlaceSearchRequest request,
            @RequestParam(required = false) Double userLatitude,
            @RequestParam(required = false) Double userLongitude
    ) {
        // 사용자 위치가 없으면 검색 위치 사용
        double userLat = userLatitude != null ? userLatitude : request.getLatitude();
        double userLng = userLongitude != null ? userLongitude : request.getLongitude();

        PlaceSearchResponse response = placeService.searchPlaces(request, userLat, userLng);
        return ResponseEntity.ok(response);
    }

    /**
     * 장소 조회수 증가 API (카드 표시 또는 클릭 시)
     * POST /api/places/{placeId}/view
     * @return 업데이트된 조회수
     */
    @PostMapping("/{placeId}/view")
    public ResponseEntity<Long> incrementViewCount(@PathVariable String placeId) {
        try {
            if (placeId == null || placeId.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            Long updatedViewCount = placeService.incrementViewCount(placeId);
            return ResponseEntity.ok(updatedViewCount);
        } catch (Exception e) {
            System.err.println("Error incrementing view count for placeId: " + placeId + " - " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}

