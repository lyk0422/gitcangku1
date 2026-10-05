package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求。日额度均为 1～100000 的整数，创建后固定。
 *
 * @param requestId           写操作全局唯一幂等键
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param attentionWeight     固定注意力权重，仅新类型公告传入，取值 1～5；
 *                            缺省（null）表示旧类型公告，不参与滚动窗口权重裁决
 * @param expectedVersion     调用方期望版本，可空；仅参与幂等身份结构化比较
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Min(1) @Max(5) Integer attentionWeight,
        Long expectedVersion
) {
    /**
     * 旧类型公告便捷构造：不带注意力权重与期望版本。
     */
    public CreateCampaignRequest(String requestId, String campaignId,
                                 Integer dailyTotalCap, Integer perVisitorDailyCap) {
        this(requestId, campaignId, dailyTotalCap, perVisitorDailyCap, null, null);
    }
}
