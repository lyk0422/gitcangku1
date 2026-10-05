package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布合格证声明请求。validFrom/validTo 为 ISO-8601 UTC 时间，区间按左闭右开解释；
 * batchNoPrefixes 为 1～5 个允许批次号前缀；同物料下前缀重叠的声明优先级必须不同。
 */
public record PublishDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @Min(value = 1, message = "version 必须 >= 1")
        @Max(value = 9999, message = "version 必须 <= 9999") int version,
        @NotBlank(message = "materialCode 不能为空") String materialCode,
        @NotNull(message = "batchNoPrefixes 不能为空")
        @Size(min = 1, max = 5, message = "batchNoPrefixes 必须包含 1～5 个前缀")
        List<@NotBlank(message = "批次号前缀不能为空") String> batchNoPrefixes,
        @Min(value = 0, message = "priority 必须 >= 0")
        @Max(value = 1000000, message = "priority 必须 <= 1000000") int priority,
        @NotNull(message = "validFrom 不能为空") Instant validFrom,
        @NotNull(message = "validTo 不能为空") Instant validTo
) {
}
