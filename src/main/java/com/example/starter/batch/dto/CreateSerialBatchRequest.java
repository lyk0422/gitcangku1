package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 新类型（序号区间）根批登记请求。requestId 为全局幂等键，operator 为操作者；
 * quantity 为批次整数数量（件），必须等于 serialIntervals 规范化后的总长度；
 * serialIntervals 为 1～10 个闭区间（相邻先合并），序号范围 1～1000000。
 */
public record CreateSerialBatchRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "productCode 不能为空") String productCode,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotNull(message = "producedAt 不能为空") Instant producedAt,
        @NotNull(message = "requiredTests 不能为空")
        @Size(min = 1, max = 5, message = "requiredTests 必须包含 1～5 个检验项")
        List<@NotBlank(message = "检验项不能为空") String> requiredTests,
        @NotNull(message = "quantity 不能为空") BigDecimal quantity,
        @NotNull(message = "serialIntervals 不能为空")
        @Size(min = 1, message = "serialIntervals 至少包含 1 个区间")
        List<@Valid SerialIntervalSpec> serialIntervals
) {
}
