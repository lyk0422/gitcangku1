package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建批次请求。producedAt 为 ISO-8601 UTC 时间；requiredTests 为 1～5 个必做检验项。
 * allergens 为过敏原集合，可空（视为空集合）；服务端逐项 trim、去重并按字典序稳定排序后持久化。
 * cleanGeneration 为生产线清洁代次，可空（默认 1），必须为正整数。
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
        @Size(max = 20, message = "allergens 最多包含 20 个过敏原")
        List<@NotBlank(message = "过敏原不能为空") String> allergens,
        @Positive(message = "cleanGeneration 必须为正整数") Integer cleanGeneration
) {
}
