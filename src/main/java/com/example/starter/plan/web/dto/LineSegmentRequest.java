package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 线路图有向区段请求项，行驶分钟为正整数。
 */
public record LineSegmentRequest(
        @NotBlank String sectionId,
        @NotBlank String fromStation,
        @NotBlank String toStation,
        @NotNull @Min(1) Integer travelMinutes) {
}
