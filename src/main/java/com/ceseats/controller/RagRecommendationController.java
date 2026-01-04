package com.ceseats.controller;

import com.ceseats.dto.RagRecommendationRequest;
import com.ceseats.service.RagRecommendationService;
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
public class RagRecommendationController {

    private static final Logger logger = LoggerFactory.getLogger(RagRecommendationController.class);

    @Autowired
    private RagRecommendationService ragRecommendationService;

    @PostMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> getRagRecommendations(
            @RequestBody RagRecommendationRequest request) {
        try {
            logger.info("[RagRecommendationController] Received RAG recommendation request - latitude: {}, longitude: {}, maxDistanceKm: {}, userPreference: {}",
                    request.getLatitude(), request.getLongitude(), request.getMaxDistanceKm(), request.getUserPreference());
            
            RagRecommendationService.RagRecommendationResult result = 
                ragRecommendationService.getRecommendations(request);
            
            Map<String, Object> response = new HashMap<>();
            response.put("stores", result.getStores());
            response.put("reason", result.getReason());
            
            logger.info("[RagRecommendationController] RAG recommendation successful - stores: {}, reason: {}",
                    result.getStores() != null ? result.getStores().size() : 0, result.getReason());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("[RagRecommendationController] Error processing RAG recommendation request", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to get RAG recommendations: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}

