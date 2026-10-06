package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物合并记录，对应 evidence_merge 表。只追加、不可变。
 * 冻结合并时两证物的身份、版本与完整保管链快照；后续主号交接不影响冻结内容。
 *
 * @param id              主键
 * @param primaryKey      主号证物业务键（保留身份的一方）
 * @param aliasKey        被合并证物业务键，合并后状态转 ALIAS
 * @param custodianId     合并时两证物共同的当前保管人（冻结）
 * @param primaryVersion  合并时主号版本（冻结，推进前）
 * @param aliasVersion    合并时副号版本（冻结，推进前）
 * @param primaryChain    合并时主号完整保管链快照 JSON（冻结）
 * @param aliasChain      合并时副号完整保管链快照 JSON（冻结）
 * @param mergedBy        合并操作人（当时的当前保管人）
 * @param createdAt       合并时间（Asia/Shanghai）
 */
public record MergeRecord(
        Long id,
        String primaryKey,
        String aliasKey,
        String custodianId,
        long primaryVersion,
        long aliasVersion,
        String primaryChain,
        String aliasChain,
        String mergedBy,
        LocalDateTime createdAt) {
}
