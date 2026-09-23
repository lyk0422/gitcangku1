package com.example.starter.exposure.budget.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建活动预算账本请求。
 *
 * @param requestId      写操作全局唯一幂等键
 * @param campaignId     活动编号，全局唯一
 * @param tenantId       租户编号
 * @param windowStartUtc 投放窗口起点（含），epoch 毫秒，UTC
 * @param windowEndUtc   投放窗口终点（不含），epoch 毫秒，UTC
 * @param audienceRule   受众规则规范化串
 * @param budget         初始总预算，非负整数，单位次
 */
public record CreateBudgetCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String tenantId,
        @NotNull Long windowStartUtc,
        @NotNull Long windowEndUtc,
        @NotBlank @Size(max = 1024) String audienceRule,
        @NotNull @Min(0) Long budget
) {
}
