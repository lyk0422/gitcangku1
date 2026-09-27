package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 采样观测提交请求：设备在采集网格于采集时刻上报一条观测，必须唯一命中一张有效许可才接收并扣减计数。
 *
 * @param requestId    全局唯一请求标识（幂等去重键；指纹 permitKey 含设备、许可版本、网格、时刻与内容）
 * @param observationId 观测记录唯一标识
 * @param deviceId     采样设备标识
 * @param gridId       采集网格单元编号（整数）
 * @param observedAtUtc 采集时刻（UTC；离线补传为实际采集时刻，按该时刻裁决许可窗口）
 * @param location     观测地点
 * @param reading      观测读数，十进制字符串，最多三位小数
 * @param note         观测备注
 */
public record SampleSubmitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull Integer gridId,
        @NotNull Instant observedAtUtc,
        @NotNull @Size(max = 512) String location,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @NotNull @Size(max = 1024) String note) {
}
