package com.example.starter.exposure.budget.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 预算转移激活请求体。在一个事务内完成整批转移；requestId 幂等，transferKey 唯一。
 *
 * @param requestId   写操作全局唯一幂等键
 * @param transferKey 转移单业务唯一键
 * @param details     转移明细（2～50 条）
 */
public record BudgetTransferActivateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String transferKey,
        @NotEmpty @Valid @Size(min = 2, max = 50) List<BudgetTransferItem> details
) {
}
