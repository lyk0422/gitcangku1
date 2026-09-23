package com.example.starter.exposure.budget.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单条预算转移明细。双方各自携带 expectedVersion 用于激活时刻的乐观版本校验。
 *
 * @param sourceCampaignId     源活动编号
 * @param targetCampaignId     目标活动编号
 * @param amount               转移数量，正整数，单位次
 * @param sourceExpectedVersion 源活动期望版本
 * @param targetExpectedVersion 目标活动期望版本
 */
public record BudgetTransferItem(
        @NotBlank @Size(max = 64) String sourceCampaignId,
        @NotBlank @Size(max = 64) String targetCampaignId,
        @NotNull @Min(1) Long amount,
        @NotNull @Min(1) Long sourceExpectedVersion,
        @NotNull @Min(1) Long targetExpectedVersion
) {
}
