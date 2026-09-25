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
 * @param category           活动类别，同意按 (访客, 类别) 裁决；缺省为 "default"
 * @param silenceStartSec    静默窗口起点（UTC 日第几秒，含），0～86399；与终点相等表示不启用
 * @param silenceEndSec      静默窗口终点（UTC 日第几秒，不含），0～86399；起点大于终点表示跨午夜
 * @param minIntervalMillis  同访客两次有效曝光最小间隔（冷却频控，毫秒）；0 表示不启用
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Size(max = 64) String category,
        @Min(0) @Max(86_399) Integer silenceStartSec,
        @Min(0) @Max(86_399) Integer silenceEndSec,
        @Min(0) Long minIntervalMillis
) {
}
