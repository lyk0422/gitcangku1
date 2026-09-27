package com.example.starter.permit;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建采样许可请求。
 *
 * @param requestId       全局唯一请求标识（幂等去重键，与观测域共用 request_log）
 * @param permitId        许可唯一标识
 * @param deviceId        绑定设备标识
 * @param permitVersion   许可版本号（同设备唯一，参与许可指纹）
 * @param gridMinLon      许可网格最小经度（含边界）
 * @param gridMinLat      许可网格最小纬度（含边界）
 * @param gridMaxLon      许可网格最大经度（含边界）
 * @param gridMaxLat      许可网格最大纬度（含边界）
 * @param validFrom       有效期开始时刻（UTC，左闭），不得早于签发时刻
 * @param validTo         有效期结束时刻（UTC，右开），必须晚于 validFrom
 * @param maxObservations 最大观测数
 */
public record CreatePermitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String permitId,
        @NotBlank @Size(max = 64) String deviceId,
        @Min(1) int permitVersion,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal gridMinLon,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal gridMinLat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal gridMaxLon,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal gridMaxLat,
        @NotNull Instant validFrom,
        @NotNull Instant validTo,
        @Min(1) int maxObservations) {
}
