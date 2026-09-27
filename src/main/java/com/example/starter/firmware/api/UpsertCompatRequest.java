package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 兼容矩阵登记/覆盖请求：某型号从 fromVersion 到 toVersion 是否允许投放。
 */
public record UpsertCompatRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @NotNull Boolean allowed,
        @NotBlank @Size(max = 64) String operator) {
}
