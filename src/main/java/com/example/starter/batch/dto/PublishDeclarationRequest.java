package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 发布合格证声明版本请求。validFrom/validTo 为 ISO-8601 UTC 时间，区间左闭右开；
 * 操作人通过 X-Actor-Id 请求头提供。
 */
public record PublishDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotNull(message = "version 不能为空") Integer version,
        @NotBlank(message = "materialCode 不能为空") String materialCode,
        @NotNull(message = "batchNoPrefix 不能为空") String batchNoPrefix,
        @NotNull(message = "priority 不能为空") Integer priority,
        @NotNull(message = "validFrom 不能为空") Instant validFrom,
        @NotNull(message = "validTo 不能为空") Instant validTo
) {
}