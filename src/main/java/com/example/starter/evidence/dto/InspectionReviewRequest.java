package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 巡检复核请求：仅异常双人确认（CONFIRMED_ANOMALY）的巡检可复核；
 * 放行后箱内隔离证物恢复 SEALED，巡检、隔离历史与带时刻结论均保留。
 *
 * @param commandKey 幂等命令键
 * @param note       复核说明，非空
 */
public record InspectionReviewRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String note) {
}
