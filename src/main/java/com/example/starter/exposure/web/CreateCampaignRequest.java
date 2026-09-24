package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.CampaignCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求。额度均为 1～100000 的整数，创建后固定；类别创建后不可变更。
 *
 * @param requestId           写操作全局唯一幂等键
 * @param campaignId          公告编号，全局唯一
 * @param category            公告类别：CRITICAL / SERVICE / MARKETING
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull CampaignCategory category,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap
) {
}
