package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 序号根批登记请求。requestId 为全局幂等键（跨操作唯一），operator 为操作者，
 * expectedVersion 为期望版本（参与幂等结构化比较）；quantity 为批次整数数量，
 * 必须等于 intervals 规范化后的总长度；intervals 为 1～10 个闭区间，
 * 范围 1..1000000，无重叠，相邻区间由服务端合并。
 */
public record RegisterSerialBatchRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "productCode 不能为空") String productCode,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotNull(message = "producedAt 不能为空") Instant producedAt,
        @NotNull(message = "requiredTests 不能为空")
        @Size(min = 1, max = 5, message = "requiredTests 必须包含 1～5 个检验项")
        List<@NotBlank(message = "检验项不能为空") String> requiredTests,
        @NotNull(message = "quantity 不能为空")
        @Min(value = 1, message = "quantity 最小为 1")
        @Max(value = 1000000, message = "quantity 最大为 1000000")
        Integer quantity,
        @NotNull(message = "intervals 不能为空")
        @Size(min = 1, max = 10, message = "intervals 必须包含 1～10 个区间")
        List<@Valid IntervalSpec> intervals
) {
}
