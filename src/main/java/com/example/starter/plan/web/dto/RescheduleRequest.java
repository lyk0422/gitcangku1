package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 原子改签请求：将路径中的已发布旧计划取消，并发布同运营日的新草稿。
 * requestKey 为幂等键；两个期望版本分别对旧、新计划做乐观校验；
 * operator 可选，提供时计入幂等指纹。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        String operator,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion) {

    /**
     * 不携带操作者的便捷构造（操作者缺省）。
     */
    public RescheduleRequest(String requestKey, String newScheduleKey,
                             Integer expectedOldVersion, Integer expectedNewVersion) {
        this(requestKey, null, newScheduleKey, expectedOldVersion, expectedNewVersion);
    }
}
