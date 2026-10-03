package com.example.starter.bridge.api;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 进桥/出桥操作请求。
 *
 * @param requestId 操作幂等键
 * @param at 实际操作时刻，UTC
 */
public record OperationRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Instant at) {
}