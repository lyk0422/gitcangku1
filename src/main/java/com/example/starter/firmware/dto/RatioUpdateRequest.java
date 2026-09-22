package com.example.starter.firmware.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发布单扩量请求。
 *
 * @param requestId       全局唯一请求号，用于幂等去重
 * @param ratio           新的投放比例，必须大于当前比例，取值 0~100
 * @param expectedVersion 期望的发布单版本，与当前版本不一致时返回 409
 */
public record RatioUpdateRequest(
        @NotBlank String requestId,
        @NotNull @Min(0) @Max(100) Integer ratio,
        @NotNull @Min(1) Integer expectedVersion) {
}
