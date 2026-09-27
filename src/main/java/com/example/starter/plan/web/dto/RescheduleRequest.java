package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 原子改签请求：将路径中的已发布旧计划取消，并发布同运营日的新草稿。
 * requestKey 为幂等键；两个期望版本分别对旧、新计划做乐观校验；
 * operator 为可选操作者标识，参与幂等指纹与发布快照。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion,
        @Size(max = 128) String operator) {
}
