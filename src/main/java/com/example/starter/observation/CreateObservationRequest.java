package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建（提交）观测记录请求。提交时设备、采集网格与采集时刻必须命中唯一有效许可，
 * 并扣减该许可的观测计数；requestId 即 permitKey，其指纹包含设备、许可版本、网格、时刻与观测内容。
 *
 * @param requestId      全局唯一请求标识（幂等去重键，提交场景即 permitKey）
 * @param observationId  观测记录唯一标识
 * @param location       观测地点
 * @param reading        观测读数，十进制字符串，最多三位小数
 * @param note           观测备注
 * @param deviceId       采集设备标识
 * @param gridX          采集网格 X 坐标
 * @param gridY          采集网格 Y 坐标
 * @param collectedAtUtc 采集时刻（UTC），须落在命中许可的左闭右开有效期内
 */
public record CreateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull Integer gridX,
        @NotNull Integer gridY,
        @NotNull Instant collectedAtUtc) {
}
