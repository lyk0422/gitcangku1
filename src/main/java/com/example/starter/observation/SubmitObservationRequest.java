package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备观测提交请求：携带设备标识与设备本地时刻，服务端按命中的偏移记录换算矫正后时刻。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param observationId   观测标识；同一观测的多次提交构成其版本序列
 * @param deviceId        提交设备标识
 * @param deviceLocalTime 设备本地时刻（ISO-8601 无区字面量，如 2026-09-25T10:00:00）
 * @param location        观测地点
 * @param reading         观测读数，十进制字符串，最多三位小数
 * @param note            观测备注
 */
public record SubmitObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank String deviceLocalTime,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
