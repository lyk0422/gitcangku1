package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批量改签单项：将已发布的 oldScheduleKey 取消并发布同运营日的新草稿 newScheduleKey。
 */
public record BatchRescheduleItem(
        @NotBlank String oldScheduleKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion) {
}
