package com.example.starter.observation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 圆弧观测离线提交（三方合并）请求：圆弧作为完整字段组参与合并，
 * 起始角与弧长只能同组接受或同组保留，不与另一提交的半组拼组。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param baseVersion 离线修改所基于的基线版本号
 * @param startAngle  起始角候选值，单位千分之一度，0..359999
 * @param arcLength   正向弧长候选值，单位千分之一度，1..180000
 * @param operator    操作者标识（非空）
 */
public record MergeArcObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer baseVersion,
        @NotNull @Min(0) @Max(359999) Integer startAngle,
        @NotNull @Min(1) @Max(180000) Integer arcLength,
        @NotBlank @Size(max = 128) String operator) {
}
