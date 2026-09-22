package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 原子改签请求：旧计划必须已发布、新计划必须为同运营日草稿，二者业务键不同；
 * expectedOldVersion / expectedNewVersion 为双方期望版本，不匹配返回 409。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String oldScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedNewVersion) {
}
