package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 领取拓扑波次请求：expectedVersion 必须等于发布单当前版本；operator 为幂等身份的一部分。
 */
public record WaveClaimRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedVersion) {
}
