package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 创建批次请求。producedAt 为 ISO-8601 UTC 时间；requiredTests 为1至5 个必做检验项。
 * recipeKey/targetMassGrams 可同时缺省（批次不绑定配方）；绑定配方版本时两者必须同时提供，
 * targetMassGrams 为目标总质量，单位克、正值、最多6位小数，绑定后不可变。
 */
public record CreateBatchRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "productCode 不能为空") String productCode,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotNull(message = "producedAt 不能为空") Instant producedAt,
        @NotNull(message = "requiredTests 不能为空")
        @Size(min = 1, max = 5, message = "requiredTests 必须包含 1至5 个检验项")
        List<@NotBlank(message = "检验项不能为空") String> requiredTests,
        String recipeKey,
        BigDecimal targetMassGrams
) {
}