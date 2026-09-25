package com.example.starter.exposure.web;

/**
 * 预占裁决预览视图（只读，不落任何状态）：按与真实申请相同的
 * 同意 → 静默 → 访客频控 → 总预算 顺序给出当前时刻的裁决结果与可区分原因。
 *
 * @param campaignId      公告编号
 * @param visitorId       访客编号
 * @param category        活动类别；null 表示该公告不校验同意
 * @param campaignVersion 活动版本（进入申请指纹）
 * @param evaluatedAtUtc  裁决时刻，epoch 毫秒，UTC
 * @param allowed         是否允许创建预占
 * @param reason          裁决原因码：ALLOWED / CONSENT_DENIED / SILENT_HOURS / QUOTA_EXHAUSTED
 * @param consentDecision 命中的最高版本同意决定 ALLOW/DENY；无生效区间或不校验同意时为 null
 * @param consentVersion  命中的最高版本同意版本号；无生效区间或不校验同意时为 null
 * @param usedTotal       公告当日已占用总额度，单位次
 * @param usedVisitor     访客当日已占用额度，单位次
 */
public record DecisionPreviewResponse(
        String campaignId,
        String visitorId,
        String category,
        int campaignVersion,
        long evaluatedAtUtc,
        boolean allowed,
        String reason,
        String consentDecision,
        Integer consentVersion,
        int usedTotal,
        int usedVisitor
) {
    /** 裁决通过原因码。 */
    public static final String ALLOWED = "ALLOWED";
}
