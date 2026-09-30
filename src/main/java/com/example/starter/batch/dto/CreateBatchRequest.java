package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建批次请求。producedAt 为 ISO-8601 UTC 时间；requiredTests 为 1～5 个必做检验项。
 * allergens 为该批次携带的过敏原标识集合（可为空或缺省，表示无过敏原），保存时统一规范化
 * （trim、去重、按代码点稳定排序）；cleaningGeneration 为批次保存时所在生产线的清洁代次，
 * 非负整数，后续仅可由清洁事件单调递增。
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
        @NotNull(message = "cleaningGeneration 不能为空")
        @PositiveOrZero(message = "cleaningGeneration 必须为非负整数")
        Integer cleaningGeneration
) {
}
