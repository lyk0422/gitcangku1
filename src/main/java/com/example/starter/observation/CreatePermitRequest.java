package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建观测采样许可请求。同一设备的有效许可在网格与时间的交集上不得重叠，重叠返回 409。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param permitId        采样许可唯一标识
 * @param deviceId        许可绑定的采集设备标识
 * @param gridXMin        网格 X 坐标下限（含）
 * @param gridXMax        网格 X 坐标上限（含）
 * @param gridYMin        网格 Y 坐标下限（含）
 * @param gridYMax        网格 Y 坐标上限（含）
 * @param validFromUtc    有效期起始时刻（UTC，含）
 * @param validToUtc      有效期结束时刻（UTC，不含）
 * @param maxObservations 许可允许的最大观测数
 * @param permitVersion   许可版本（permitVersion）
 */
public record CreatePermitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String permitId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull Integer gridXMin,
        @NotNull Integer gridXMax,
        @NotNull Integer gridYMin,
        @NotNull Integer gridYMax,
        @NotNull Instant validFromUtc,
        @NotNull Instant validToUtc,
        @NotNull @Min(1) Integer maxObservations,
        @NotNull @Min(1) Integer permitVersion) {
}
