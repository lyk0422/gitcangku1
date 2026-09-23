package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建活动预算账本请求。每个 campaign 在一个左闭右开 UTC 投放窗口内持有非负整数预算。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param campaignId    活动编号，全局唯一
 * @param tenantId      租户编号
 * @param windowStartUtc 投放窗口起始时刻（含），epoch 毫秒，UTC
 * @param windowEndUtc  投放窗口结束时刻（不含），epoch 毫秒，UTC，必须大于起始时刻
 * @param audienceRule  受众规则标识
 * @param initialBudget 初始总预算，单位次，非负整数
 */
public record CreateBudgetAccountRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String tenantId,
        @NotNull Long windowStartUtc,
        @NotNull Long windowEndUtc,
        @NotBlank @Size(max = 256) String audienceRule,
        @NotNull @Min(0) @Max(1_000_000) Integer initialBudget
) {
}
