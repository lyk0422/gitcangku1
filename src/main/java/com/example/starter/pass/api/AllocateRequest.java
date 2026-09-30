package com.example.starter.pass.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 接收时隙分配申请。
 *
 * @param requestId    调用方幂等 requestId
 * @param satelliteCode 卫星业务编码
 * @param stationCode  接收站点编码（一部天线）
 * @param windowStart  申请接收窗口起点（含），UTC
 * @param windowEnd    申请接收窗口终点（不含），UTC
 */
public record AllocateRequest(
        @NotBlank String requestId,
        @NotBlank String satelliteCode,
        @NotBlank String stationCode,
        @NotNull Instant windowStart,
        @NotNull Instant windowEnd
) {
}
