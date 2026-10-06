package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 领取拓扑波次请求。operator 为非空操作者标识，计入幂等指纹。
 */
public record ClaimWaveRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator) {
}
