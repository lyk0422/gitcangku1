package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 创建批次请求。producedAt 为 ISO-8601 UTC 时间；requiredTests 为 1～5 个必做检验项。
 * allergens 可省略（省略视为空集合），服务端规范化：逐项 trim、去重并按字典序稳定排序；
 * cleaningGeneration 可省略（省略默认为 1），必须为正整数，创建后只能通过清洁操作向前推进。
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
        @Size(max = 20, message = "allergens 至多包含 20 个过敏原")
        List<@NotBlank(message = "过敏原名称不能为空") String> allergens,
        @Positive(message = "cleaningGeneration 必须为正整数")
        Integer cleaningGeneration
) {
}
