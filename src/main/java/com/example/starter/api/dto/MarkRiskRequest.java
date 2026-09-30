package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 标记制品版本依赖风险级别的请求体，riskLevel 取 NONE/LOW/MEDIUM/HIGH/CRITICAL。
 */
public record MarkRiskRequest(@NotBlank String riskLevel) {
}
