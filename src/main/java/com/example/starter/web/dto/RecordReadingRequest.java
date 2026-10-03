package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放电读数记录请求。
 *
 * @param requestId 幂等请求键
 * @param cellCode 电芯编号
 * @param voltage 测量电压，单位伏特(V)
 * @param measuredAt 测量时刻，UTC（ISO-8601）
 */
public record RecordReadingRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "cellCode 不能为空") @Size(max = 64) String cellCode,
        @NotNull(message = "voltage 不能为空") @DecimalMin(value = "0", message = "voltage 不能为负")
        BigDecimal voltage,
        @NotNull(message = "measuredAt 不能为空") Instant measuredAt) {
}