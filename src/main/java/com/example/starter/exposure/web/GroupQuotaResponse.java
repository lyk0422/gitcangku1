package com.example.starter.exposure.web;

import java.time.LocalDate;
import java.util.List;

/**
 * 归并组额度视图：组成员、组内合计及原编号分账。
 *
 * @param groupId             归并组编号
 * @param version             组当前乐观锁版本
 * @param members             组内全部成员编号，按编号升序
 * @param campaignId          公告编号
 * @param utcDate             查询的 UTC 日，格式 yyyy-MM-dd
 * @param perVisitorDailyCap  组适用的每访客每 UTC 日上限（原每访客上限），单位次
 * @param usedGroup           组内所有编号当日已确认与有效预占合计，单位次；归并前已超限时可大于上限
 * @param remainingGroup      组当日剩余额度，单位次；合计已超限时为 0（不为负）
 * @param breakdown           按原申请编号分账
 * @param settledAtUtc        查询前结算过期预占所用的当前时刻，epoch 毫秒，UTC
 */
public record GroupQuotaResponse(
        String groupId,
        int version,
        List<String> members,
        String campaignId,
        LocalDate utcDate,
        int perVisitorDailyCap,
        int usedGroup,
        int remainingGroup,
        List<MemberUsage> breakdown,
        long settledAtUtc
) {
    /**
     * 原编号分账。
     *
     * @param visitorId   原申请编号
     * @param usedVisitor 该编号当日已确认与有效预占合计，单位次
     */
    public record MemberUsage(String visitorId, int usedVisitor) {
    }
}
