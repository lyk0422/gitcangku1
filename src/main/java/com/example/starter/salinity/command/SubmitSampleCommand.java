package com.example.starter.salinity.command;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 采样提交命令。
 *
 * @param voyageCode   航次编号，非空
 * @param stationCode  站位编号，非空
 * @param depthM       深度（米），必填，允许 0 至 500
 * @param salinityPsu  盐度（PSU），必填，允许 0 至 45
 * @param collectedUtc 采集 UTC 时刻，必填
 * @param sampleNo     采样编号，非空，全局唯一
 * @param requestId    请求 ID，可空；非空时全局唯一，用于同参重放
 */
public record SubmitSampleCommand(
        @NotBlank(message = "航次编号不能为空")
        String voyageCode,

        @NotBlank(message = "站位编号不能为空")
        String stationCode,

        @NotNull(message = "深度不能为空")
        @Min(value = 0, message = "深度不得小于0米")
        @Max(value = 500, message = "深度不得大于500米")
        Integer depthM,

        @NotNull(message = "盐度不能为空")
        @DecimalMin(value = "0.0", message = "盐度不得小于0 PSU")
        @DecimalMax(value = "45.0", message = "盐度不得大于45 PSU")
        BigDecimal salinityPsu,

        @NotNull(message = "采集时刻不能为空")
        Instant collectedUtc,

        @NotBlank(message = "采样编号不能为空")
        String sampleNo,

        String requestId
) {
}
