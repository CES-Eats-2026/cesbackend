package com.ceseats.controller;

import com.ceseats.dto.RecommendationRequest;
import com.ceseats.dto.RecommendationResponse;
import com.ceseats.service.RecommendationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://ceseats.store", "https://cesfront.vercel.app", "http://localhost:3000"})
public class StoreSaveController {

    @Autowired
    private RecommendationService recommendationService;

    @PostMapping("/recommendations")
    public ResponseEntity<RecommendationResponse> getRecommendations(
            @RequestBody RecommendationRequest request) {
        RecommendationResponse response = recommendationService.getRecommendations(request);
        return ResponseEntity.ok(response);
    }
}

