package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 封锁切换映射项：一个旧计划指定一个现有 DRAFT 替代计划。
 */
public record DisruptionMappingItem(
        @NotBlank String oldScheduleKey,
        @NotBlank String replacementScheduleKey) {
}
