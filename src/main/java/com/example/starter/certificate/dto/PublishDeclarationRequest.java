package com.example.starter.certificate.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 发布合格证声明请求。validFrom/validTo 为 ISO-8601 UTC 时间，区间按左闭右开解释。
 */
public record PublishDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotNull(message = "version 不能为空") @Min(value = 1, message = "version 必须 >= 1") Integer version,
        @NotBlank(message = "materialCode 不能为空") String materialCode,
        @NotBlank(message = "batchNoPrefix 不能为空") String batchNoPrefix,
        @NotNull(message = "priority 不能为空") Integer priority,
        @NotNull(message = "validFrom 不能为空") Instant validFrom,
        @NotNull(message = "validTo 不能为空") Instant validTo
) {
}
