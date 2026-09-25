package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 召回解除申请请求体；申请人通过 X-Actor-Id 请求头提供。
 * releaseKey 为幂等键，指纹含召回版本、规范化复检集合、纠正措施和指定审批人；
 * 同键同参重放返回首次结果，同键改参返回 409，失败不占键。
 * retestBatches 为复检批次集合，服务端去重并规范排序后存储。
 */
public record RecallReleaseApplyRequest(
        @NotBlank(message = "releaseKey 不能为空") String releaseKey,
        @NotNull(message = "recallVersion 不能为空")
        @Min(value = 1, message = "recallVersion 必须大于等于 1") Integer recallVersion,
        @NotBlank(message = "correctiveMeasures 不能为空") String correctiveMeasures,
        @NotEmpty(message = "retestBatches 不能为空")
        List<@NotBlank(message = "复检批次键不能为空") String> retestBatches,
        @NotBlank(message = "approver 不能为空") String approver
) {
}
