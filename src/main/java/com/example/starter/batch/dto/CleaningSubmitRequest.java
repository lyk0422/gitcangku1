package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 清洗提交请求。residual 为残留量十进制字符串，单位毫克，范围 0~100、最多 6 位小数，
 * 按原文存储不做舍入；requestId 为幂等键。
 */
public record CleaningSubmitRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "prevBatchKey 不能为空") String prevBatchKey,
        @NotBlank(message = "nextBatchKey 不能为空") String nextBatchKey,
        @NotBlank(message = "residual 不能为空")
        @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,6})?$", message = "residual 必须为 0~100 的十进制字符串，最多 6 位小数")
        String residual
) {
}