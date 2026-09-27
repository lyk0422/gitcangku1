package com.example.starter.sampling;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 提交观测请求（单次提交与批量合并条目共用）。
 * permitKey 幂等键由设备、许可版本、网格、时刻与观测内容指纹生成，同键重放、失败不占键。
 *
 * @param deviceId       观测设备标识
 * @param permitVersion  许可版本号，须与命中许可一致
 * @param gridX          网格X坐标
 * @param gridY          网格Y坐标
 * @param collectedAtUtc 采集时刻（UTC，ISO-8601）
 * @param content        观测内容
 */
public record SubmitObservationRequest(
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull @Min(1) Integer permitVersion,
        @NotNull Integer gridX,
        @NotNull Integer gridY,
        @NotNull Instant collectedAtUtc,
        @NotNull @Size(max = 1024) String content) {
}
