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

    @Autowired
    private com.ceseats.service.RagAsyncStreamService ragAsyncStreamService;

    @PostMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> getRagRecommendations(
            @RequestBody RagRecommendationRequest request) {
        // ver.2: Redis Streams 기반 비동기 처리
        final long t0 = System.nanoTime();
        String requestId = ragAsyncStreamService.enqueueLlmRequest(request);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "PROCESSING");
        response.put("requestId", requestId != null ? requestId.trim() : null);
        log.info("[RAG][{}] request accepted: enqueueMs={}", requestId, (System.nanoTime() - t0) / 1_000_000L);
        return ResponseEntity.ok(response);
    }

    /**
     * 클라이언트 polliing api
     * GET /api/rag/requests/{requestId}
     */
    @GetMapping("/requests/{requestId}")
    public ResponseEntity<Map<String, Object>> getRagRequestStatus(@PathVariable String requestId) {
        String normalizedRequestId = requestId != null ? requestId.trim() : null;
        Map<String, Object> res = new HashMap<>();
        res.put("requestId", normalizedRequestId);

        String status = ragAsyncStreamService.getStatus(normalizedRequestId);
        res.put("status", status != null ? status : "NOT_FOUND");

        if ("DONE".equalsIgnoreCase(status)) {
            String resultJson = ragAsyncStreamService.getResultJson(normalizedRequestId);
            if (resultJson != null) {
                try {
                    // JSON 문자열 그대로 응답에 포함 (프론트에서 바로 파싱 가능)
                    res.put("result", new com.fasterxml.jackson.databind.ObjectMapper().readTree(resultJson));
                } catch (Exception e) {
                    res.put("result", resultJson);
                }
            }
        } else if ("ERROR".equalsIgnoreCase(status)) {
            res.put("error", ragAsyncStreamService.getError(normalizedRequestId));
        }

        return ResponseEntity.ok(res);
    }
}

