package com.ceseats.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 장소 검색 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaceSearchRequest {
    private Double latitude;
    private Double longitude;
    /** 반경 (미터). 예: 5000 = 5km. 미지정/0 이면 5km 사용 */
    private Integer radius = 5000;
    private String sortBy; // "price_asc", "view_desc"
}

