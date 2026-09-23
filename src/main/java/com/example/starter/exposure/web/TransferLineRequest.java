package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 预算转移明细请求行。双方 expectedVersion 为激活时的乐观锁校验值。
 *
 * @param sourceCampaignId      源活动编号（预算转出方）
 * @param targetCampaignId      目标活动编号（预算转入方）
 * @param amount                转移数量，单位次，正整数
 * @param sourceExpectedVersion 源活动账本期望版本号
 * @param targetExpectedVersion 目标活动账本期望版本号
 */
public record TransferLineRequest(
        @NotBlank @Size(max = 64) String sourceCampaignId,
        @NotBlank @Size(max = 64) String targetCampaignId,
        @NotNull @Min(1) @Max(1_000_000) Integer amount,
        @NotNull @Min(0) Integer sourceExpectedVersion,
        @NotNull @Min(0) Integer targetExpectedVersion
) {
}
