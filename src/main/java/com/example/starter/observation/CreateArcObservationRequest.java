package com.example.starter.observation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建圆弧观测请求：圆弧为完整字段组，起始角与正向弧长不可拆分。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 观测记录唯一标识
 * @param startAngle    起始角，单位千分之一度，0..359999，区间含两端并按模 360000 绕回
 * @param arcLength     正向弧长，单位千分之一度，1..180000
 * @param operator      操作者标识（非空）
 */
public record CreateArcObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Min(0) @Max(359999) Integer startAngle,
        @NotNull @Min(1) @Max(180000) Integer arcLength,
        @NotBlank @Size(max = 128) String operator) {
}
