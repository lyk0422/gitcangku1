package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 召回解除申请请求体；申请人通过 X-Actor-Id 请求头提供。
 * releaseKey 为解除业务键：指纹含召回版本、规范化复检集合、纠正措施与审批人，
 * 同键同参重放返回原申请，不同参数返回 409，失败不占键。
 * reinspectionBatches 为复检批次集合，服务端规范化为去空白、去重、字典序排序后存储。
 */
public record RecallReleaseApplyRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "releaseKey 不能为空") String releaseKey,
        @NotNull(message = "recallVersion 不能为空")
        @Positive(message = "recallVersion 必须为正整数") Integer recallVersion,
        @NotBlank(message = "correctiveAction 不能为空") String correctiveAction,
        @NotNull(message = "reinspectionBatches 不能为空")
        @Size(min = 1, message = "reinspectionBatches 至少包含 1 个批次")
        List<@NotBlank(message = "复检批次键不能为空") String> reinspectionBatches
) {
}
