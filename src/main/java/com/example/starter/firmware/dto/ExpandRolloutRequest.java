package com.example.starter.firmware.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 扩量（修改投放比例）请求。
 *
 * @param requestId       全局唯一请求 ID，用于幂等去重
 * @param expectedVersion 期望的发布单当前版本号，不一致返回 409
 * @param ratio           新的投放比例 0~100，不得小于当前比例
 */
public record ExpandRolloutRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull @Min(0) @Max(100) Integer ratio) {
}
