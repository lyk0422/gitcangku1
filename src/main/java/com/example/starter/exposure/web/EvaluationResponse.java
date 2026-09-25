package com.example.starter.exposure.web;

/**
 * 预占裁决预校验（只读 dry-run）视图：按正式预占相同顺序评估 同意 → 静默 → 频控 → 预算，
 * 不创建预占、不扣额度，用于查询“若此刻申请会因何原因被拒”。
 *
 * @param campaignId 公告编号
 * @param version    评估时活动版本
 * @param category   评估时活动类别
 * @param visitorId  访客编号
 * @param atUtc      评估所用请求时刻，epoch 毫秒，UTC
 * @param allowed    是否可创建预占
 * @param reason     被拒原因码（见 {@link FailureReason}）；allowed=true 时为 null
 * @param consentVersion 命中的同意版本；未命中有效同意时为 null
 */
public record EvaluationResponse(
        String campaignId,
        int version,
        String category,
        String visitorId,
        long atUtc,
        boolean allowed,
        String reason,
        Long consentVersion
) {
}
