package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建批次请求。producedAt 为 ISO-8601 UTC 时间；requiredTests 为 1～5 个必做检验项。
 * quantity 与 maxSampleQuantity 同时缺省为普通批次（无数量概念）；
 * 同时提供则为新类型整数数量批次：quantity 为正整数总数量，
 * maxSampleQuantity 为不可变最大取样量，取值 0 至 quantity。
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
        @Min(value = 1, message = "quantity 必须为正整数") Integer quantity,
        @Min(value = 0, message = "maxSampleQuantity 不能为负数") Integer maxSampleQuantity
) {
}
