package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 原子改签请求：将路径中的已发布旧计划取消，并发布同运营日的新草稿。
 * requestKey 为幂等键；两个期望版本分别对旧、新计划做乐观校验。
 * crew 为新计划值乘乘务组：不传或为空时新计划不经过乘务门禁、不写乘务快照（兼容路径）；
 * 传入时新计划发布同样执行资格门禁，且乘务组参与幂等哈希。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion,
        @Valid List<CrewMemberItem> crew) {

    /** 不携带乘务组的兼容构造（新计划不走乘务门禁）。 */
    public RescheduleRequest(String requestKey, String newScheduleKey,
                             Integer expectedOldVersion, Integer expectedNewVersion) {
        this(requestKey, newScheduleKey, expectedOldVersion, expectedNewVersion, null);
    }
}
