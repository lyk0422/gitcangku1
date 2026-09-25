package com.example.starter.exposure.domain;

/**
 * 访客同意记录 PO。每个 (访客, 类别) 下的有效声明构成互不重叠的 UTC 时间轴，
 * 区间 [{@link #effectiveStartUtc}, {@link #effectiveEndUtc}) 左闭右开，单位 epoch 毫秒。
 *
 * <p>同一 consentVersion 在被新区间截断出左右两段残片时会对应多行（同一版本与决定，
 * 不同 consentId），以维持“有效区间不得重叠”不变量。</p>
 *
 * @param consentId        同意记录编号（残片行各自独立）
 * @param visitorId        合成访客编号
 * @param category         活动类别
 * @param decision         ALLOW 或 DENY
 * @param consentVersion   同意版本，同 (访客, 类别) 下直接提交的版本不得重复；DENY 不可被更低版本覆盖
 * @param effectiveStartUtc 生效起点（含），epoch 毫秒，UTC
 * @param effectiveEndUtc  生效终点（不含），epoch 毫秒，UTC
 * @param status           记录状态
 * @param createdAtUtc     提交（写入）时刻，epoch 毫秒，UTC
 * @param withdrawnAtUtc   撤回时刻；未撤回为 null
 */
public record Consent(
        String consentId,
        String visitorId,
        String category,
        ConsentDecision decision,
        long consentVersion,
        long effectiveStartUtc,
        long effectiveEndUtc,
        ConsentStatus status,
        long createdAtUtc,
        Long withdrawnAtUtc
) {
}
