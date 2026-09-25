package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 观测提交请求：携带设备、经纬度、基准版本、采集时刻与三个可编辑字段的完整值。
 * 服务端按提交的基准版本将原始坐标转换为统一基准坐标，原始坐标与原基准版本不可改写。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 观测记录唯一标识
 * @param deviceId      提交设备标识
 * @param frameVersion  提交时的坐标基准版本（原基准版本）
 * @param latitude      原始纬度（度），合法范围 [-90, 90]，越界返回 422
 * @param longitude     原始经度（度），合法范围 [-180, 180]，越界返回 422
 * @param capturedAt    采集时刻（UTC，ISO-8601）
 * @param location      观测地点
 * @param reading       观测读数，十进制字符串，最多三位小数
 * @param note          观测备注
 */
public record SubmitObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 32) String frameVersion,
        @NotNull Double latitude,
        @NotNull Double longitude,
        @NotNull Instant capturedAt,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
