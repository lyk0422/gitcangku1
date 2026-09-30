package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度读数登记请求。readAt 为采集时刻（ISO-8601 UTC），temperature 单位℃。
 * requestId 为读数幂等键：同键同参重放首次结果，同键换温度（或其他参数）返回 409。
 */
public record TemperatureReadingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "readAt 不能为空") Instant readAt,
        @NotNull(message = "temperature 不能为空") BigDecimal temperature
) {
}
