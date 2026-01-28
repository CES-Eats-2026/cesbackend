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
    private List<String> types;

}

