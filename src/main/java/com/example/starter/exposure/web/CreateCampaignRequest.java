package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建公告请求。额度均为 1～100000 的整数，创建后固定；
 * 冷却分钟数可选，缺省 0（不限制），创建后只能通过携带 expectedVersion 的修改接口变更。
 *
 * @param requestId           写操作全局唯一幂等键
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次
 * @param cooldownMinutes     同一访客两次 CONFIRMED 曝光之间的最短冷却分钟数，
 *                            取值 0～1440，0 表示不限制；缺省（null）按 0 处理
 */
public record CreateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) @Max(100_000) Integer dailyTotalCap,
        @NotNull @Min(1) @Max(100_000) Integer perVisitorDailyCap,
        @Min(0) @Max(1440) Integer cooldownMinutes
) {
    /** 冷却分钟数缺省按 0（不限制）处理。 */
    public int effectiveCooldownMinutes() {
        return cooldownMinutes == null ? 0 : cooldownMinutes;
    }
}
