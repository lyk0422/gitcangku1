package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 原子改签请求：将路径中的已发布旧计划取消，并发布同运营日的新草稿。
 * requestKey 为幂等键；两个期望版本分别对旧、新计划做乐观校验；
 * operator 为操作者标识（可空），参与幂等指纹（计划集合、限速版本与操作者）。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion,
        String operator) {

    /**
     * 兼容构造：不含操作者。
     */
    public RescheduleRequest(String requestKey, String newScheduleKey,
                             Integer expectedOldVersion, Integer expectedNewVersion) {
        this(requestKey, newScheduleKey, expectedOldVersion, expectedNewVersion, null);
    }
}
