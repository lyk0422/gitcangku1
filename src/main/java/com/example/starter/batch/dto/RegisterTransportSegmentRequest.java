package com.example.starter.batch.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 登记运输段请求。运输段左闭右开 [startAt, endAt)，同一批次不得重叠；
 * minTemp/maxTemp 为允许温度上下限（含边界，单位摄氏度，最多两位小数）。
 */
public record RegisterTransportSegmentRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "segmentKey 不能为空") String segmentKey,
        @NotNull(message = "startAt 不能为空") Instant startAt,
        @NotNull(message = "endAt 不能为空") Instant endAt,
        @NotNull(message = "minTemp 不能为空")
        @Digits(integer = 8, fraction = 2, message = "minTemp 最多两位小数")
        BigDecimal minTemp,
        @NotNull(message = "maxTemp 不能为空")
        @Digits(integer = 8, fraction = 2, message = "maxTemp 最多两位小数")
        BigDecimal maxTemp
) {
}
