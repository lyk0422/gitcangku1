package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批量改签中的单个改签项：将 oldScheduleKey 取消并发布同运营日草稿 newScheduleKey。
 */
public record BatchRescheduleItemRequest(
        @NotBlank String oldScheduleKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion) {
}
