package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 借出人冻结状态视图。累计追缴次数达到阈值即自动冻结；
 * 解冻后有效计数（reclaimCount）从零重新累计，总次数（totalReclaimCount）保留历史。
 *
 * @param borrowerId         借出人
 * @param frozen             当前是否处于冻结状态
 * @param reclaimCount       有效追缴计数（自最近一次解冻以来；未解冻过则为全部）
 * @param totalReclaimCount  历史累计追缴总次数（不含解冻抵减）
 * @param freezeThreshold    冻结阈值（达到即冻结）
 * @param lastUnfrozenAt     最近一次解冻时间（Asia/Shanghai）；null 表示从未解冻
 * @param lastUnfrozenBy     最近一次解冻操作人；null 表示从未解冻
 */
public record BorrowerFreezeView(
        String borrowerId,
        boolean frozen,
        int reclaimCount,
        int totalReclaimCount,
        int freezeThreshold,
        LocalDateTime lastUnfrozenAt,
        String lastUnfrozenBy) {
}
