package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求。额度均为 1～100000 的整数，创建后固定。
 *
 * @param requestId          写操作全局唯一幂等键
 * @param campaignId         公告编号，全局唯一
 * @param dailyTotalCap      每 UTC 日总额度，单位次
 * @param perVisitorDailyCap 每访客每 UTC 日上限，单位次
 * @param category           活动类别；访客同意按 访客+类别 裁决；null 表示不启用同意裁决
 * @param silentStartMinute  静默时段起点（UTC 日内分钟，0～1439）；null 表示无静默时段
 * @param silentEndMinute    静默时段终点（UTC 日内分钟，1～1440，左闭右开）；不大于起点表示跨 UTC 午夜
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Size(max = 64) String category,
        @Min(0) @Max(1439) Integer silentStartMinute,
        @Min(1) @Max(1440) Integer silentEndMinute
) {

    /** 兼容历史入口：不启用同意裁决（无类别）且无静默时段。 */
    public CreateCampaignRequest(String requestId, String campaignId,
                                 Integer dailyTotalCap, Integer perVisitorDailyCap) {
        this(requestId, campaignId, dailyTotalCap, perVisitorDailyCap, null, null, null);
    }
}
