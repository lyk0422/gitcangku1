package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 证物合并事件视图。身份、版本与两链快照均为合并时刻的冻结值，
 * 不随后续主号交接/借出变化；副号历史仍按原号读取。
 *
 * @param primaryKey     主号证物业务键（别名查询时即指向的最终主号）
 * @param aliasKey       被合并证物业务键（已转 ALIAS）
 * @param custodianId    合并时两证物共同的当前保管人（冻结）
 * @param primaryVersion 合并时主号版本（冻结，推进前）
 * @param aliasVersion   合并时副号版本（冻结，推进前）
 * @param primaryChain   合并时主号完整保管链快照 JSON（冻结）
 * @param aliasChain     合并时副号完整保管链快照 JSON（冻结）
 * @param mergedBy       合并操作人
 * @param mergedAt       合并时间（Asia/Shanghai）
 */
public record MergeView(
        String primaryKey,
        String aliasKey,
        String custodianId,
        long primaryVersion,
        long aliasVersion,
        String primaryChain,
        String aliasChain,
        String mergedBy,
        LocalDateTime mergedAt) {
}
