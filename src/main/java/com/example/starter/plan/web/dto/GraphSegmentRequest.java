package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 线路图有向区段定义。minutes 为正整数行驶分钟。
 */
public record GraphSegmentRequest(
        @NotBlank String segmentId,
        @NotBlank String fromStation,
        @NotBlank String toStation,
        @Min(1) int minutes) {
}
