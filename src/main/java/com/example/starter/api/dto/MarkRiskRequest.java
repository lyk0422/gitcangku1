package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 为指定制品版本标记（或更新）依赖风险等级的请求。
 *
 * @param name      依赖制品名称
 * @param version   依赖制品精确版本号，正整数
 * @param riskLevel 风险等级代码：NONE/LOW/MEDIUM/HIGH/CRITICAL
 * @param reason    风险来源或说明，可为空
 */
public record MarkRiskRequest(
        @NotBlank String name,
        @Positive int version,
        @NotBlank String riskLevel,
        @Size(max = 512) String reason) {
}
