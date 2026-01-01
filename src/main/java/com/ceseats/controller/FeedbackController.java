package com.ceseats.controller;

import com.ceseats.dto.request.FeedbackRequest;
import com.ceseats.service.FeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"https://ceseats.store", "https://cesfront.vercel.app", "http://localhost:3000"})
public class FeedbackController {

    @Autowired
    private FeedbackService feedbackService;

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> sendFeedback(@RequestBody FeedbackRequest request) {
        try {
            if (request.getFeedback() == null || request.getFeedback().trim().isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "피드백 내용을 입력해주세요.");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            feedbackService.sendFeedbackToDiscord(
                request.getFeedback(),
                request.getImageBase64(),
                request.getImageName()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "피드백이 전송되었습니다.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            System.err.println("Error processing feedback: " + e.getMessage());
            e.printStackTrace();
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "피드백 전송에 실패했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}

