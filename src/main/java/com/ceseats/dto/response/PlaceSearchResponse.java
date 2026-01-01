package com.ceseats.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 장소 검색 응답 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaceSearchResponse {
    private List<PlaceResponse> places;
    private Integer totalCount;
}

