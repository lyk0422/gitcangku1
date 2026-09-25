package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 原子改签请求：将路径中的已发布旧计划取消，并发布同运营日的新草稿。
 * requestKey 为幂等键；两个期望版本分别对旧、新计划做乐观校验。
 * driver/conductor 同时缺省时新计划按无乘务发布，二者必须同时提供且为不同人员。
 */
public record RescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String newScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedNewVersion,
        String operator,
        @Valid CrewAssignmentRequest driver,
        @Valid CrewAssignmentRequest conductor) {

    /** 兼容旧调用：不含乘务指派。 */
    public RescheduleRequest(String requestKey, String newScheduleKey,
                             int expectedOldVersion, int expectedNewVersion) {
        this(requestKey, newScheduleKey, expectedOldVersion, expectedNewVersion, null, null, null);
    }
}
