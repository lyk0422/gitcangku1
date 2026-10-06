package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.PrepareResult;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 组准备回执请求：expectedGeneration 必须等于组当前代次，旧代次回执 409 拒绝。
 * result 只接受 PREPARED/FAILED；SUCCESS 无法解析，按 400 拒绝（预备屏障不接受普通成功回执）。
 */
public record GroupPrepareRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedGeneration,
        @NotNull PrepareResult result) {
}
