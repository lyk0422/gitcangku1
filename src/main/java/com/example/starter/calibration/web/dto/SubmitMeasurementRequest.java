package com.example.starter.calibration.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 提交测量请求。读数与上下限为最多 6 位小数的十进制字符串。
 */
public record SubmitMeasurementRequest(
        @NotBlank(message = "measurementKey 不能为空") String measurementKey,
        @NotBlank(message = "instrumentId 不能为空") String instrumentId,
        @NotBlank(message = "measuredAt 不能为空") String measuredAt,
        @NotBlank(message = "rawReading 不能为空") String rawReading,
        @NotBlank(message = "lowerLimit 不能为空") String lowerLimit,
        @NotBlank(message = "upperLimit 不能为空") String upperLimit,
        @NotBlank(message = "submittedBy 不能为空") String submittedBy) {
}
