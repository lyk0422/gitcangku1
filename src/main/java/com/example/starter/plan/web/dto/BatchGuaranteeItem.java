package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批量保障改签单条目：将 AT_RISK 的后车旧计划原子改签为新草稿。
 * 两个期望版本分别对旧、新计划做乐观校验。
 */
public record BatchGuaranteeItem(
        @NotBlank String successorScheduleKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion) {
}
