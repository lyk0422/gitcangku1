package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 清洗记录提交请求。residualAmount 为残留量十进制字符串，单位毫克，
 * 范围 0～100、最多 6 位小数，判定使用未舍入原值；
 * expectedVersion 为提交前该（线, 前批, 后批）组合已有的清洗记录数。
 */
public record SubmitCleaningRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "cleaningId 不能为空") String cleaningId,
        @NotBlank(message = "fromBatchKey 不能为空") String fromBatchKey,
        @NotBlank(message = "toBatchKey 不能为空") String toBatchKey,
        @NotBlank(message = "residualAmount 不能为空") String residualAmount,
        @NotNull(message = "expectedVersion 不能为空")
        @Min(value = 0, message = "expectedVersion 不能为负") Long expectedVersion
) {
}