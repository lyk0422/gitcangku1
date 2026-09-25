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
 * @param cooldownMinutes     同一访客两次 CONFIRMED 之间的最短冷却分钟数，0～1440；
 *                            缺省或 null 表示 0（不限制）
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Min(0) @Max(1440) Integer cooldownMinutes
) {
    /**
     * 兼容四参构造：不指定冷却分钟数时按 0（不限制）处理。
     */
    public CreateCampaignRequest(String requestId, String campaignId,
                                 Integer dailyTotalCap, Integer perVisitorDailyCap) {
        this(requestId, campaignId, dailyTotalCap, perVisitorDailyCap, null);
    }

    /**
     * 归一化后的冷却分钟数；null 视为 0。
     */
    public int cooldownMinutesOrZero() {
        return cooldownMinutes == null ? 0 : cooldownMinutes;
    }
}
