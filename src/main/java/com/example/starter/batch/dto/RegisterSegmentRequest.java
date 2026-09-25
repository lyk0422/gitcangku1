package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 运输段登记请求。起止为 UTC 时刻，区间左闭右开；温度限最多两位小数（摄氏度，含边界）。
 * 运输录入人通过 X-Actor-Id 请求头提供。
 */
public record RegisterSegmentRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "segmentKey 不能为空") String segmentKey,
        @NotNull(message = "startAt 不能为空") Instant startAt,
        @NotNull(message = "endAt 不能为空") Instant endAt,
        @NotNull(message = "minTemp 不能为空") BigDecimal minTemp,
        @NotNull(message = "maxTemp 不能为空") BigDecimal maxTemp
) {
}
