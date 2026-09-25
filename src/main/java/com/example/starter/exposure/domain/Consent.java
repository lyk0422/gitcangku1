package com.example.starter.exposure.domain;

/**
 * 访客活动类别同意区间 PO。同意按 访客 + 类别 维度提交，
 * 生效区间 [{@link #effectiveStartUtc}, {@link #effectiveEndUtc}) 左闭右开，
 * {@code effectiveEndUtc} 为 {@code null} 表示长期有效。
 *
 * <p>同一访客同一类别可存在多条不同版本区间：重叠时刻按 {@link #consentVersion}
 * 高者裁决；服务层保证同版本区间不重叠，且 DENY 不被更低版本覆盖。
 * 撤回只影响之后的预占，不改变已有预占固化的同意快照。</p>
 *
 * @param consentId         同意记录编号
 * @param visitorId         合成访客编号
 * @param category          活动类别
 * @param decision          同意决定 ALLOW/DENY
 * @param consentVersion    同意版本号，正整数，高版本在重叠区间优先
 * @param effectiveStartUtc 生效起点，epoch 毫秒，UTC（含）
 * @param effectiveEndUtc   生效终点，epoch 毫秒，UTC（不含）；null 表示长期有效
 * @param createdAtUtc      提交时刻，epoch 毫秒，UTC
 */
public record Consent(
        String consentId,
        String visitorId,
        String category,
        ConsentDecision decision,
        int consentVersion,
        long effectiveStartUtc,
        Long effectiveEndUtc,
        long createdAtUtc
) {
}
