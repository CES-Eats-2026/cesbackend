package com.ceseats.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRequest {
    private String feedback;
    // 이미지는 base64로 인코딩하여 전송하거나, 별도로 처리
    private String imageBase64; // 선택사항
    private String imageName; // 선택사항
}

