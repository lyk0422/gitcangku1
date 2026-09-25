package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Consent;

/**
 * 同意区间视图。
 *
 * @param consentId         同意记录编号
 * @param visitorId         访客编号
 * @param category          活动类别
 * @param decision          同意决定 ALLOW/DENY
 * @param consentVersion    同意版本号
 * @param effectiveStartUtc 生效起点，epoch 毫秒，UTC（含）
 * @param effectiveEndUtc   生效终点，epoch 毫秒，UTC（不含）；null 表示长期有效
 * @param createdAtUtc      提交时刻，epoch 毫秒，UTC
 */
public record ConsentResponse(
        String consentId,
        String visitorId,
        String category,
        String decision,
        int consentVersion,
        long effectiveStartUtc,
        Long effectiveEndUtc,
        long createdAtUtc
) {
    public static ConsentResponse from(Consent consent) {
        return new ConsentResponse(
                consent.consentId(),
                consent.visitorId(),
                consent.category(),
                consent.decision().name(),
                consent.consentVersion(),
                consent.effectiveStartUtc(),
                consent.effectiveEndUtc(),
                consent.createdAtUtc());
    }
}
