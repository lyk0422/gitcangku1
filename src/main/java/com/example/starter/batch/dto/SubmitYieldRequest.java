package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 产率平衡核算提交请求；四类数量均三位小数，单位与批次产品计量单位一致，不允许为负。
 * 同一 requestId 重试返回首次偏差与状态；同一 requestId 换数量返回 409。
 */
public record SubmitYieldRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "inputQty 投料数量不能为空") BigDecimal inputQty,
        @NotNull(message = "goodQty 合格产出数量不能为空") BigDecimal goodQty,
        @NotNull(message = "lossQty 损耗数量不能为空") BigDecimal lossQty,
        @NotNull(message = "scrapQty 报废数量不能为空") BigDecimal scrapQty
) {
}
