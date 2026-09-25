package com.example.starter.observation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记设备时钟偏移请求：为设备新增一条偏移记录，生效起始时刻相同即区间重叠（409）。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param effectiveFromUtc 偏移生效起始时刻（UTC，ISO-8601，如 2026-09-25T02:00:00Z）
 * @param offsetSeconds    偏移秒数（-86400 至 86400 整数）
 */
public record RegisterOffsetRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank String effectiveFromUtc,
        @NotNull @Min(-86400) @Max(86400) Integer offsetSeconds) {
}
