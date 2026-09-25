package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 上传温度读数请求。recordedAt 必须落在所属运输段 [startAt, endAt) 内，
 * 且严格晚于该段已有全部读数的 recordedAt，违反返回 422。
 */
public record UploadReadingRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "recordedAt 不能为空") Instant recordedAt,
        @NotNull(message = "temperature 不能为空") BigDecimal temperature
) {
}
