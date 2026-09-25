package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温度读数上传请求：readAt 必须落在所属段 [startAt,endAt) 内，且同段严格递增。
 */
public record ReadingRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "readAt 不能为空") Instant readAt,
        @NotNull(message = "temperature 不能为空") BigDecimal temperature
) {
}
