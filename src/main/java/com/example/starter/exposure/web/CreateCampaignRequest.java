package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求。额度均为 1～100000 的整数，创建后固定；
 * 归因窗口为 1～1440 的 UTC 分钟数，创建后可按版本调整。
 *
 * @param requestId                 写操作全局唯一幂等键
 * @param campaignId                公告编号，全局唯一
 * @param dailyTotalCap             每 UTC 日总额度，单位次
 * @param perVisitorDailyCap        每访客每 UTC 日上限，单位次
 * @param attributionWindowMinutes  UTC 归因窗口分钟数（要求值），1～1440，区间左闭右开
 * @param operatorId                创建操作者编号；可空，空值表示未声明
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @NotNull @Min(1) @Max(1440) Integer attributionWindowMinutes,
        @Size(max = 64) String operatorId
) {
}
