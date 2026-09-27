package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 批量改签中的单个改签项：取消已发布旧计划并发布同运营日新草稿。
 *
 * @param oldScheduleKey     被改签的已发布旧计划业务键
 * @param newScheduleKey     改签后发布的新草稿业务键
 * @param expectedOldVersion 旧计划期望版本（乐观校验）
 * @param expectedNewVersion 新草稿期望版本（乐观校验）
 */
public record BatchRescheduleItemRequest(
        @NotBlank String oldScheduleKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion) {
}
