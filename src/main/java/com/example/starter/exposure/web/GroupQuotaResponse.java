package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 组维度额度视图：按 公告/访客所在组/UTC 日 汇总，给出组成员、合计及原编号分账。
 * 组上限沿用公告的每访客每日上限；公告总额度仍按原计数，不在此视图重复。
 *
 * @param campaignId         公告编号
 * @param visitorId          查询入口访客编号
 * @param groupId            所属归并组编号；未入组为 null
 * @param utcDate            查询的 UTC 日，格式 yyyy-MM-dd
 * @param perVisitorDailyCap 组当日上限（即公告每访客每日上限），单位次
 * @param usedGroup          组当日已确认与有效预占合计（全部成员账目求和），单位次
 * @param remainingGroup     组当日剩余额度，单位次；合计超上限时归零，不为负
 * @param members            原编号分账：每个成员各自的当日占用，按编号字典序
 * @param settledAtUtc       查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 */
public record GroupQuotaResponse(
        String campaignId,
        String visitorId,
        String groupId,
        LocalDate utcDate,
        int perVisitorDailyCap,
        int usedGroup,
        int remainingGroup,
        List<MemberUsage> members,
        long settledAtUtc
) {
    /**
     * 单个原编号的分账。
     *
     * @param visitorId   原申请编号
     * @param usedVisitor 该编号当日已确认与有效预占合计，单位次
     */
    public record MemberUsage(String visitorId, int usedVisitor) {
    }
}
