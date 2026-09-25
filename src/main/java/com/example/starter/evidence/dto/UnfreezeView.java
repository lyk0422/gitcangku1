package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 解冻结果视图。解冻后追缴计数从零重新累计，历史追缴记录保留。
 *
 * @param borrowerId 被解冻的借出人
 * @param unfrozenBy 提交解冻的保管人
 * @param note       解冻说明
 * @param unfrozenAt 解冻时间（Asia/Shanghai）
 */
public record UnfreezeView(
        String borrowerId,
        String unfrozenBy,
        String note,
        LocalDateTime unfrozenAt) {
}
