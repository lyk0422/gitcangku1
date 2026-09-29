package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 产率平衡核算提交请求。四类数量单位与批次一致，均为三位小数；
 * requestId 为核算幂等键，同键同数量重放返回首次偏差与状态，同键换数量返回 409。
 */
public record YieldReconcileRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "inputQuantity 投料数量不能为空") BigDecimal inputQuantity,
        @NotNull(message = "qualifiedQuantity 合格产出数量不能为空") BigDecimal qualifiedQuantity,
        @NotNull(message = "lossQuantity 损耗数量不能为空") BigDecimal lossQuantity,
        @NotNull(message = "scrapQuantity 报废数量不能为空") BigDecimal scrapQuantity
) {
}
