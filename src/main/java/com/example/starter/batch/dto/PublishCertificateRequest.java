package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布合格证声明请求。validFrom/validTo 为 ISO-8601 UTC 时间，区间左闭右开；
 * prefixes 为 1～10 个允许批号前缀，priority 数值越大越优先。
 */
public record PublishCertificateRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotNull(message = "version 不能为空") Integer version,
        @NotBlank(message = "materialCode 不能为空") String materialCode,
        @NotNull(message = "prefixes 不能为空")
        @Size(min = 1, max = 10, message = "prefixes 必须包含 1～10 个前缀")
        List<@Valid PrefixSpec> prefixes,
        @NotNull(message = "validFrom 不能为空") Instant validFrom,
        @NotNull(message = "validTo 不能为空") Instant validTo
) {

    /**
     * 声明前缀规格：prefix 为允许批号前缀；priority 为优先级，数值越大越优先，
     * 相互重叠（互为前缀）的前缀优先级不得相同，否则 422。
     */
    public record PrefixSpec(
            @NotBlank(message = "prefix 不能为空") String prefix,
            @NotNull(message = "priority 不能为空")
            @PositiveOrZero(message = "priority 不得为负") Integer priority
    ) {
    }
}