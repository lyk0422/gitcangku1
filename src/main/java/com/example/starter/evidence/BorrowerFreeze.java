package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 借出人冻结状态实体，对应 borrower_freeze 表。每个借出人至多一行：
 * 累计被追缴达到冻结阈值即自动冻结；解冻后追缴计数清零重新累计。
 *
 * @param borrowerId   借出人标识
 * @param frozen       是否冻结中：true 冻结（禁止新借出）/ false 正常
 * @param reclaimCount 上次解冻以来累计被追缴次数；解冻后清零
 * @param frozenBy     触发冻结的追缴保管人；null 表示未冻结
 * @param frozenAt     冻结触发时间（Asia/Shanghai）；null 表示未冻结
 * @param updatedAt    最近一次变更时间（Asia/Shanghai）
 */
public record BorrowerFreeze(
        String borrowerId,
        boolean frozen,
        int reclaimCount,
        String frozenBy,
        LocalDateTime frozenAt,
        LocalDateTime updatedAt) {
}
