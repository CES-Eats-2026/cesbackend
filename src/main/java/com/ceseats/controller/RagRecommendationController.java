package com.ceseats.controller;

import com.ceseats.dto.RagRecommendationRequest;
import com.ceseats.service.RagRecommendationService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = {"https://ceseats.store", "https://cesfront.vercel.app", "http://localhost:3000"})
@Slf4j
public class RagRecommendationController {

    @Autowired
    private RagRecommendationService ragRecommendationService;

    @PostMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> getRagRecommendations(
            @RequestBody RagRecommendationRequest request) {
        try {
            
            RagRecommendationService.RagRecommendationResult result = 
                ragRecommendationService.getRecommendations(request);
            
            Map<String, Object> response = new HashMap<>();
            response.put("stores", result.getStores());
            response.put("reason", result.getReason());
            response.put("isRandom", result.isRandom()); //랜덤 반환 여부

            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            //에러 발생 시 랜덤 5개 반환
            try {
                RagRecommendationService.RagRecommendationResult randomResult = 
                    ragRecommendationService.getRandomStores(5, request.getLatitude(), request.getLongitude());
                
                Map<String, Object> response = new HashMap<>();
                response.put("stores", randomResult.getStores());
                response.put("reason", randomResult.getReason());
                response.put("isRandom", true);
                
                return ResponseEntity.ok(response);
            } catch (Exception fallbackException) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "Failed to get recommendations: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
            }
        }
    }
}

