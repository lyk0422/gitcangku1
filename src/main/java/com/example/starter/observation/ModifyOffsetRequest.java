package com.example.starter.observation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改设备时钟偏移请求：仅修改既有偏移记录的偏移秒数，生效起始时刻不可变。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param effectiveFromUtc 目标偏移记录的生效起始时刻（UTC，ISO-8601）
 * @param offsetSeconds    新的偏移秒数（-86400 至 86400 整数）
 */
public record ModifyOffsetRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank String effectiveFromUtc,
        @NotNull @Min(-86400) @Max(86400) Integer offsetSeconds) {
}
