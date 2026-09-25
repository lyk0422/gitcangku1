package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 解冻记录实体，对应 unfreeze_record 表。记录只追加、不可变；
 * 解冻后借出人追缴计数从零重新累计，历史追缴记录保留。
 *
 * @param id                    主键
 * @param borrowerId            被解冻的借出人
 * @param unfrozenBy            提交解冻的保管人（须为另一人，非借出人本人）
 * @param note                  解冻说明
 * @param reclaimCountAtUnfreeze 解冻时刻该借出人累计追缴次数，作为重新计数的基线
 * @param createdAt             解冻时间（Asia/Shanghai）
 */
public record UnfreezeRecord(
        Long id,
        String borrowerId,
        String unfrozenBy,
        String note,
        int reclaimCountAtUnfreeze,
        LocalDateTime createdAt) {
}
