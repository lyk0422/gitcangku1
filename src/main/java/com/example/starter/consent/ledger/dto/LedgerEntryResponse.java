package com.example.starter.consent.ledger.dto;

import java.time.Instant;

import com.example.starter.consent.ledger.LedgerEntryType;
import com.example.starter.consent.ledger.QuotaStatus;

/**
 * 账本条目响应（历史证据）：字段一旦写入不再被后续操作改写，排序按 seq 升序稳定。
 *
 * <p>null 含义：
 * <ul>
 *   <li>{@code objectCount}/{@code objectDigest}/{@code querySnapshot} 仅 QUERY 条目有值，其余为 null；</li>
 *   <li>{@code balanceBefore} 在额度首次声明时为 null（此前无余额行）；</li>
 *   <li>{@code balanceAfter} 在 CLEAR 条目及其后状态下为 null（已清除，无可用余额）；</li>
 *   <li>{@code status} 为条目裁决后额度状态，CLEAR 条目中为 CLEARED。</li>
 * </ul>
 *
 * @param seq           全局单调递增账本序号，稳定排序依据
 * @param requestId     产生本条目的幂等请求标识
 * @param subjectKey    操作者（主体标识）
 * @param operation     操作类型：QUOTA_ADJUST / QUERY / CLEAR
 * @param purpose       用途
 * @param epoch         授权代次
 * @param status        裁决后额度状态：ACTIVE / CLEARED
 * @param objectCount   本次核销对象数量（单位：个），非 QUERY 条目为 null
 * @param objectDigest  规范化对象集合摘要（SHA-256 十六进制），非 QUERY 条目为 null
 * @param balanceBefore 扣减/调整前余额（单位：个），首次声明为 null
 * @param balanceAfter  扣减/调整后余额（单位：个），清除后为 null
 * @param fingerprint   覆盖操作者、操作类型、规范化集合与全部结果字段的指纹
 * @param querySnapshot 首次完整成功查询响应快照（JSON），非 QUERY 条目为 null
 * @param occurredAtUtc 裁决时间（UTC，微秒精度）
 */
public record LedgerEntryResponse(
        long seq,
        String requestId,
        String subjectKey,
        LedgerEntryType operation,
        String purpose,
        int epoch,
        QuotaStatus status,
        Integer objectCount,
        String objectDigest,
        Long balanceBefore,
        Long balanceAfter,
        String fingerprint,
        String querySnapshot,
        Instant occurredAtUtc) {
}
