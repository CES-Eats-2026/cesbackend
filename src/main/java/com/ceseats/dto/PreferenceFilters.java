package com.ceseats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * LLM이 파싱한 사용자 선호도 필터
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreferenceFilters {
    private List<String> cuisineTypes; // ["korean", "japanese", "italian"]
    private List<String> placeTypes; // ["restaurant", "cafe", "fast_food_restaurant"]
    private Integer minPriceLevel; // 1-3
    private Integer maxPriceLevel; // 1-3
    private Double minRating; // 0.0-5.0
    private List<String> keywords; // ["spicy", "vegetarian", "quick"]
    private String mealType; // "breakfast", "lunch", "dinner", "snack"
}

