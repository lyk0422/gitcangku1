package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求。额度均为 1～100000 的整数，创建后固定；
 * 归因窗口为 1～1440 分钟，缺省使用服务端默认值，创建后可通过窗口调整接口变更（版本递增）。
 *
 * @param requestId                 写操作全局唯一幂等键
 * @param campaignId                公告编号，全局唯一
 * @param dailyTotalCap             每 UTC 日总额度，单位次
 * @param perVisitorDailyCap        每访客每 UTC 日上限，单位次
 * @param attributionWindowMinutes  UTC 归因窗口，单位分钟；为 null 时使用默认值
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Min(1) @Max(1440) Integer attributionWindowMinutes
) {
    /** 兼容旧调用：不显式指定归因窗口时使用服务端默认值。 */
    public CreateCampaignRequest(String requestId, String campaignId,
                                 Integer dailyTotalCap, Integer perVisitorDailyCap) {
        this(requestId, campaignId, dailyTotalCap, perVisitorDailyCap, null);
    }
}
