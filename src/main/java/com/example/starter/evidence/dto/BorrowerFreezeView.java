package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 借出人冻结状态视图。
 *
 * @param borrowerId   借出人标识
 * @param frozen       是否冻结中：true 冻结（禁止新借出）/ false 正常
 * @param reclaimCount 上次解冻以来累计被追缴次数；解冻后清零
 * @param frozenBy     触发冻结的追缴保管人；null 表示未冻结
 * @param frozenAt     冻结触发时间（Asia/Shanghai）；null 表示未冻结
 * @param reason       冻结原因说明；null 表示未冻结
 */
public record BorrowerFreezeView(
        String borrowerId,
        boolean frozen,
        int reclaimCount,
        String frozenBy,
        LocalDateTime frozenAt,
        String reason) {
}
