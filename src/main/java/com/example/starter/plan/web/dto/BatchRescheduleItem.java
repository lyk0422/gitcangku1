package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批量改签单项：将已发布旧计划取消并发布同运营日新草稿，期望版本分别做乐观校验。
 */
public record BatchRescheduleItem(
        @NotBlank String oldScheduleKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion) {
}
