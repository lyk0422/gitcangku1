package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批兼容诊断请求体：只校验不执行，返回各来源状态与缺失的兼容级别对。
 */
public record MergeDiagnoseRequest(
        @NotBlank(message = "containerKey 不能为空") String containerKey,
        @NotNull(message = "sourceBatchKeys 不能为空")
        @Size(min = 1, max = 8, message = "sourceBatchKeys 必须包含 1～8 个来源批次")
        List<@NotBlank(message = "来源批次 batchKey 不能为空") String> sourceBatchKeys
) {
}
