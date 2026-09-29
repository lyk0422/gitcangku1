package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 超限偏差处置确认请求。disposerId 通过 X-Actor-Id 请求头提供；
 * version 为处置人确认的核算版本，版本过期（不是批次最新版本）返回 422。
 */
public record DisposeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "version 不能为空") Long version
) {
}
