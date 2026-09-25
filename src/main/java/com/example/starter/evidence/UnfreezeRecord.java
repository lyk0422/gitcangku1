package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 解冻记录实体，对应 unfreeze_record 表。记录只追加、不可变；
 * 解冻后该借出人的追缴计数从零重新累计，历史追缴记录保留。
 *
 * @param id         主键
 * @param borrowerId 被解冻的借出人
 * @param actorId    提交解冻的保管人（须不同于触发冻结的保管人且非借出人本人）
 * @param note       解冻说明
 * @param createdAt  解冻时间（Asia/Shanghai）
 */
public record UnfreezeRecord(
        Long id,
        String borrowerId,
        String actorId,
        String note,
        LocalDateTime createdAt) {
}
