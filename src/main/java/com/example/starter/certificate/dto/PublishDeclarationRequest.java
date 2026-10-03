package com.example.starter.certificate.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布合格证声明版本请求。validFrom/validTo 为 ISO-8601 UTC 时间，区间左闭右开；
 * prefixes 为 1～5 个允许批次号前缀；priority 数值越大越优先。
 */
public record PublishDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @Min(value = 1, message = "version 必须不小于 1") int version,
        @NotBlank(message = "supplierId 不能为空") String supplierId,
        @NotBlank(message = "materialCode 不能为空") String materialCode,
        @NotNull(message = "prefixes 不能为空")
        @Size(min = 1, max = 5, message = "prefixes 必须包含 1～5 个前缀")
        List<@NotBlank(message = "前缀不能为空") String> prefixes,
        @Min(value = 0, message = "priority 不能为负")
        @Max(value = 1000000, message = "priority 过大") int priority,
        @NotNull(message = "validFrom 不能为空") Instant validFrom,
        @NotNull(message = "validTo 不能为空") Instant validTo
) {
}