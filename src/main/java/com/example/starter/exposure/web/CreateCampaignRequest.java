package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求。额度均为 1～100000 的整数，创建后固定。
 *
 * @param requestId           写操作全局唯一幂等键
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param activityId          归属活动编号；null 表示创建时不归入任何活动（之后可迁移）
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Size(max = 64) String activityId
) {
    /** 兼容历史调用：不归属活动的创建请求。 */
    public CreateCampaignRequest(String requestId, String campaignId,
                                 Integer dailyTotalCap, Integer perVisitorDailyCap) {
        this(requestId, campaignId, dailyTotalCap, perVisitorDailyCap, null);
    }
}
