package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 原子改签请求：将路径中的已发布旧计划取消，并发布同运营日的新草稿。
 * requestKey 为幂等键，指纹包含计划集合、当前生效限速版本与操作者；
 * 两个期望版本分别对旧、新计划做乐观校验；operator 缺省按 "system" 处理。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion,
        String operator) {

    /**
     * 兼容构造：不携带操作者，按 "system" 计入幂等指纹。
     */
    public RescheduleRequest(String requestKey, String newScheduleKey,
                             Integer expectedOldVersion, Integer expectedNewVersion) {
        this(requestKey, newScheduleKey, expectedOldVersion, expectedNewVersion, null);
    }
}