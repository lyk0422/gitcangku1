package com.example.starter.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接收任务登记请求：任务编号、天线编号、UTC 起止时刻。
 */
public record CreateTaskRequest(
        @NotBlank String taskCode,
        @NotBlank String antennaCode,
        @NotNull Instant startAt,
        @NotNull Instant endAt) {
}