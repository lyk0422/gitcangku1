package com.example.starter.exposure.budget.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 预算曝光预占申请。按活动当前总预算校验，在途 + 已确认不得超过总预算。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param campaignId 活动编号
 * @param visitorId  合成访客编号
 */
public record BudgetApplyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String visitorId
) {
}
