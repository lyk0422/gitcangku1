package com.example.starter.incident;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 追加处置记录请求。occurredAt 为处置发生的 UTC 时间（ISO-8601）。
 */
public record AppendActionRequest(
        @NotBlank @Size(max = 128) String commandKey,
        @NotBlank @Size(max = 128) String actionKey,
        @NotNull Instant occurredAt,
        @NotBlank @Size(max = 64) String type,
        @NotBlank @Size(max = 1024) String description) {
}
