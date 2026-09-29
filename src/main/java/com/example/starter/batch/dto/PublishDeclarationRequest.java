package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布合格证声明请求。同一物料每次发布生成一个递增版本；
 * 有效区间 [validFrom, validTo) 左闭右开，全部 UTC。
 * 允许批号前缀 1～10 个且请求内不得重复；与同物料同优先级 ACTIVE 声明前缀重叠返回 422。
 */
public record PublishDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotBlank(message = "materialCode 不能为空") String materialCode,
        int priority,
        @NotNull(message = "validFrom 不能为空") Instant validFrom,
        @NotNull(message = "validTo 不能为空") Instant validTo,
        @NotEmpty(message = "allowedBatchNoPrefixes 不能为空")
        @Size(max = 10, message = "allowedBatchNoPrefixes 最多包含 10 个前缀")
        List<@NotBlank(message = "批号前缀不能为空") String> allowedBatchNoPrefixes
) {
}
