package com.example.starter.permit;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单条观测提交请求：设备、采集网格与采集时刻必须唯一命中一个有效许可方可接收。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param deviceId  采集设备标识
 * @param gridLon   采集网格点经度
 * @param gridLat   采集网格点纬度
 * @param sampledAt 采集时刻（UTC），不得晚于提交时刻
 * @param content   观测内容原文（参与 permitKey 指纹）
 */
public record SubmitObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal gridLon,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal gridLat,
        @NotNull Instant sampledAt,
        @NotBlank @Size(max = 2048) String content) {
}
