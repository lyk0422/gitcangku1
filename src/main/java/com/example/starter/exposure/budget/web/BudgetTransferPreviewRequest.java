package com.example.starter.exposure.budget.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 预算转移预览请求体。仅携带 2～50 条明细，按完整后态返回各活动账本，不写数据。
 *
 * @param details 转移明细（2～50 条）
 */
public record BudgetTransferPreviewRequest(
        @NotEmpty @Valid @Size(min = 2, max = 50) List<BudgetTransferItem> details
) {
}
