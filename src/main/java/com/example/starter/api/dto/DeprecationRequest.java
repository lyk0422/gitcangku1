package com.example.starter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 单个弃用窗口登记请求。
 *
 * <p>effectiveAt 为弃用生效 UTC 时刻，不得早于服务端当前时钟；
 * 替代版本必须与被弃用版本同名且未撤回。
 */
public record DeprecationRequest(
        @NotBlank String name,
        @Positive int version,
        @NotNull
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant effectiveAt,
        @Positive int replacementVersion,
        @Size(max = 512) String note) {
}
