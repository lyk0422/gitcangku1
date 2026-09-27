package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建采样许可请求。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param permitId       许可唯一标识
 * @param deviceId       被授权设备标识
 * @param permitVersion  许可版本，创建后不可变
 * @param gridMin        授权网格区间下界（闭区间，整数网格单元编号）
 * @param gridMax        授权网格区间上界（闭区间，整数网格单元编号）
 * @param validFromUtc   有效期起点（UTC，含）；不得早于服务端当前时刻，禁止追溯
 * @param validToUtc     有效期终点（UTC，不含），必须晚于起点
 * @param maxObservations 最大观测数（正整数，单位：条）
 */
public record CreatePermitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String permitId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String permitVersion,
        @NotNull Integer gridMin,
        @NotNull Integer gridMax,
        @NotNull Instant validFromUtc,
        @NotNull Instant validToUtc,
        @NotNull @Min(1) Integer maxObservations) {
}
