package com.example.starter.observation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 设备时钟偏移登记/修改请求：登记按 (deviceId, effectiveFromUtc) 定位，区间重叠返回 409；
 * 修改按相同键定位既有记录，仅允许变更偏移秒数。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param effectiveFromUtc 偏移生效起始 UTC 时刻（含，ISO-8601）
 * @param offsetSeconds    偏移秒数（-86400 至 86400 整数）：矫正后时刻 = 设备本地时刻 + 偏移秒数
 */
public record OffsetChangeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Instant effectiveFromUtc,
        @NotNull @Min(-86400) @Max(86400) Integer offsetSeconds) {
}
