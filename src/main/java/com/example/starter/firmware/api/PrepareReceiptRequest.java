package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.PrepareResult;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 组成员 PREPARE 回执请求：PREPARED 表示目标已暂存但不改设备当前版本；
 * expectedPrepareGeneration 必须匹配组当前准备代次，旧代次回执不影响新组。
 */
public record PrepareReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) Integer expectedPrepareGeneration,
        @NotNull PrepareResult result) {
}
