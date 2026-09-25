package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Consent;

/**
 * 同意记录视图。
 *
 * @param consentId         同意记录编号（区间被截断时每个残片有独立编号）
 * @param visitorId         访客编号
 * @param category          活动类别
 * @param decision          ALLOW 或 DENY
 * @param consentVersion    同意版本
 * @param effectiveStartUtc 生效起点（含），epoch 毫秒，UTC
 * @param effectiveEndUtc   生效终点（不含），epoch 毫秒，UTC
 * @param status            ACTIVE / WITHDRAWN / SUPERSEDED
 * @param createdAtUtc      提交时刻，epoch 毫秒，UTC
 * @param withdrawnAtUtc    撤回时刻；未撤回为 null
 */
public record ConsentResponse(
        String consentId,
        String visitorId,
        String category,
        String decision,
        long consentVersion,
        long effectiveStartUtc,
        long effectiveEndUtc,
        String status,
        long createdAtUtc,
        Long withdrawnAtUtc
) {
    public static ConsentResponse from(Consent c) {
        return new ConsentResponse(
                c.consentId(),
                c.visitorId(),
                c.category(),
                c.decision().name(),
                c.consentVersion(),
                c.effectiveStartUtc(),
                c.effectiveEndUtc(),
                c.status().name(),
                c.createdAtUtc(),
                c.withdrawnAtUtc());
    }
}
