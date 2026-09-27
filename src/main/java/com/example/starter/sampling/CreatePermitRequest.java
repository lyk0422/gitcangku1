package com.example.starter.sampling;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建采样许可请求。网格范围为闭区间，有效期为 UTC 左闭右开。
 *
 * @param permitId        许可唯一标识
 * @param deviceId        观测设备标识
 * @param gridMinX        网格范围最小X（含）
 * @param gridMaxX        网格范围最大X（含）
 * @param gridMinY        网格范围最小Y（含）
 * @param gridMaxY        网格范围最大Y（含）
 * @param validFromUtc    有效期起点（UTC，左闭，ISO-8601）
 * @param validToUtc      有效期终点（UTC，右开，ISO-8601）
 * @param maxObservations 最大观测数上限
 * @param permitVersion   许可版本号
 */
public record CreatePermitRequest(
        @NotBlank @Size(max = 64) String permitId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull Integer gridMinX,
        @NotNull Integer gridMaxX,
        @NotNull Integer gridMinY,
        @NotNull Integer gridMaxY,
        @NotNull Instant validFromUtc,
        @NotNull Instant validToUtc,
        @NotNull @Min(1) Integer maxObservations,
        @NotNull @Min(1) Integer permitVersion) {
}
