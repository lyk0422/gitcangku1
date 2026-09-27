package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 创建批次请求。producedAt 为 ISO-8601 UTC 时间；requiredTests 为 1～5 个必做检验项。
 *
 * <p>效价登记为可选的整组字段：quantity/initialPotency/minReleasePotency/dailyDecayRate
 * 要么同时提供（登记效价模型 v1），要么同时省略（旧路径，不做效价放行与权重守恒校验）。
 * 字段缺失组合由服务端判 400；数值为负或数量非正判 422。
 *
 * @param quantity           批次数量（无量纲）；登记效价时必须为正
 * @param initialPotency     初始效价（效价单位），非负
 * @param minReleasePotency  最低放行效价阈值，非负
 * @param dailyDecayRate     按 UTC 日线性衰减率（效价单位/日），非负，可为 0
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
        BigDecimal quantity,
        BigDecimal initialPotency,
        BigDecimal minReleasePotency,
        BigDecimal dailyDecayRate
) {
}
