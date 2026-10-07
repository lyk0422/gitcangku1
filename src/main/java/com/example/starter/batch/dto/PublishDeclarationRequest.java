package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 发布合格证声明请求。每次发布为 declarationKey 生成下一个版本号；
 * validFrom/validTo 为 ISO-8601 UTC 时间，区间左闭右开，validFrom 必须早于 validTo；
 * batchNoPrefix 允许空串，表示匹配任意批号。
 */
public record PublishDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotBlank(message = "materialCode 不能为空") String materialCode,
        @NotNull(message = "batchNoPrefix 不能为 null") String batchNoPrefix,
        @NotNull(message = "priority 不能为空") Integer priority,
        @NotNull(message = "validFrom 不能为空") Instant validFrom,
        @NotNull(message = "validTo 不能为空") Instant validTo
) {
}
