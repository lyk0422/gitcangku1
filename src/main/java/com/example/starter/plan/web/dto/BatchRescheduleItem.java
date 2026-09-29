package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批量改签中的单项：将已发布旧计划原子改签为同运营日的新草稿。
 * 两个期望版本分别对旧、新计划做乐观校验。
 */
public record BatchRescheduleItem(
        @NotBlank String oldScheduleKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion) {
}
