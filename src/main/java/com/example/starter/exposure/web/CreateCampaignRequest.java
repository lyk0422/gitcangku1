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
 * @param channelKey          初始归属渠道编号；null/空表示不归属任何渠道，申请不受渠道频控
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Size(max = 64) String channelKey
) {
    /** 兼容无渠道创建：等价于 channelKey 为 null。 */
    public CreateCampaignRequest(String requestId, String campaignId,
                                 Integer dailyTotalCap, Integer perVisitorDailyCap) {
        this(requestId, campaignId, dailyTotalCap, perVisitorDailyCap, null);
    }

    /** 归一化渠道键：空白视为未归属。 */
    public String normalizedChannelKey() {
        return (channelKey == null || channelKey.isBlank()) ? null : channelKey;
    }
}
