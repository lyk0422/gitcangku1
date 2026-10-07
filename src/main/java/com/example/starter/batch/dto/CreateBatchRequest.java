package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建批次请求。producedAt 为 ISO-8601 UTC 时间；requiredTests 为 1～5 个必做检验项。
 * specKey/specVersion 可选，须同时提供或同时缺省；提供时批次绑定该版特性规范，
 * 放行前必须提交该版本的 PASS 测量向量。
 */
public record CreateBatchRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "productCode 不能为空") String productCode,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotNull(message = "producedAt 不能为空") Instant producedAt,
        @NotNull(message = "requiredTests 不能为空")
        @Size(min = 1, max = 5, message = "requiredTests 必须包含 1～5 个检验项")
        List<@NotBlank(message = "检验项不能为空") String> requiredTests,
        String specKey,
        Integer specVersion
) {
}
