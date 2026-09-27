package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 区组扩容请求：只能新建后继随机表版本，必须显式引用该区组已封存的当前最新版本。
 * 提交前研究侧先核对旧版本未分配余量；服务端复核不守恒（实际值≠要求值）时整次 422。
 *
 * @param sealKey           已封存版本的封存密钥，用于显式引用前驱版本
 * @param expectedRemaining 研究侧要求的前驱版本当前未分配名额（单位：个），用于守恒核对
 * @param newCapacity       后继版本区组容量（单位：个），正偶数，A/B 各半
 */
public record ExpandTableRequest(
        @NotBlank(message = "sealKey 不能为空")
        String sealKey,
        @NotNull(message = "expectedRemaining 不能为空")
        @Min(value = 0, message = "expectedRemaining 不能为负")
        Integer expectedRemaining,
        @NotNull(message = "newCapacity 不能为空")
        @Min(value = 2, message = "后继版本容量必须为不小于 2 的偶数")
        @Max(value = 64, message = "后继版本容量不能超过 64")
        Integer newCapacity
) {
}
