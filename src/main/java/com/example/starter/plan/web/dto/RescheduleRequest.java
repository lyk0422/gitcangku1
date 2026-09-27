package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 原子改签请求：将路径中的已发布旧计划取消，并发布同运营日的新草稿。
 * requestKey 为幂等键；两个期望版本分别对旧、新计划做乐观校验；
 * operator 为操作者，写入新计划发布快照，为空时服务端以 system 代替。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion,
        @Size(max = 64) String operator) {

    /**
     * 未显式指定操作者的兼容构造器，操作者缺省为 system。
     */
    public RescheduleRequest(String requestKey, String newScheduleKey,
                             Integer expectedOldVersion, Integer expectedNewVersion) {
        this(requestKey, newScheduleKey, expectedOldVersion, expectedNewVersion, null);
    }
}
