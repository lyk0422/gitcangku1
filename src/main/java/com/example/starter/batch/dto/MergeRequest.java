package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体。allergenKey 为幂等键，指纹含来源批次成分版本、规范化过敏原代码、
 * 目标容器与操作类型；同键同参重放返回首次结果，失败不占键。
 * 先校验全部来源状态与容器兼容矩阵，任一不兼容整次 422/409 并回滚全部血缘与库存。
 */
public record MergeRequest(
        @NotBlank(message = "allergenKey 不能为空") String allergenKey,
        @NotBlank(message = "targetBatchKey 不能为空") String targetBatchKey,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotBlank(message = "containerKey 不能为空") String containerKey,
        @NotNull(message = "sourceBatchKeys 不能为空")
        @Size(min = 2, max = 5, message = "sourceBatchKeys 必须包含 2～5 个来源批次")
        List<@NotBlank(message = "来源批次 batchKey 不能为空") String> sourceBatchKeys
) {
}
