package com.example.starter.allocation.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接收时隙分配请求。
 *
 * @param requestId 幂等键，同键同参重放返回原结果，同键异参返回 409
 * @param predictionKey 预测业务键
 * @param expectedVersion 期望的预测版本，为空表示接受当前版本；与当前版本不一致时拒绝
 * @param stationCode 接收站代码
 * @param slotStartUtc 申请时隙起点，UTC，须落在预测窗口内
 * @param slotEndUtc 申请时隙终点，UTC，须落在预测窗口内
 */
public record AllocationRequest(
        @NotBlank String requestId,
        @NotBlank String predictionKey,
        Integer expectedVersion,
        @NotBlank String stationCode,
        @NotNull Instant slotStartUtc,
        @NotNull Instant slotEndUtc) {
}