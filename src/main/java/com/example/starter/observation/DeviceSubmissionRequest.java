package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 设备观测提交请求：携带设备标识与设备本地时刻，服务端按命中的偏移记录换算矫正后时刻。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param submissionId  观测提交唯一标识（观测标识）
 * @param deviceId      采集设备唯一标识
 * @param deviceLocalAt 设备本地时刻（无时区，ISO-8601，如 2026-09-25T10:00:00）
 * @param location      观测地点候选值
 * @param reading       观测读数候选值，十进制字符串，最多三位小数
 * @param note          观测备注候选值
 */
public record DeviceSubmissionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String submissionId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull LocalDateTime deviceLocalAt,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
