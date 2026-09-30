package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仓储温度读数登记请求。readingTime 为采集时刻（ISO-8601 UTC），temperature 为摄氏度(℃)；
 * requestId 在请求路径外由请求体提供，同键重放返回首次结果，同键换温度/时刻/仓储段返回 409。
 */
public record RegisterReadingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "readingTime 不能为空，UTC 采集时刻") Instant readingTime,
        @NotNull(message = "temperature 不能为空，单位℃") BigDecimal temperature
) {
}
