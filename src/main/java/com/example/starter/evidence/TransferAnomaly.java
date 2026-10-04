package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 在途异常记录实体，对应 transfer_anomaly 表。记录只追加、不可变；
 * 记录后交接立即冻结，仅可由原保管人取消，后续新交接不修改本链。
 *
 * @param id              主键
 * @param transferId      所属交接记录 id
 * @param evidenceKey     关联证物业务键
 * @param checkpointSeq   发生异常时的检查点序号
 * @param reason          异常原因
 * @param expectedSealNo  期望封条编号（证物当前封条）
 * @param submittedSealNo 签到提交的封条编号
 * @param arrivedAtUtc    签到提交的到达时刻（UTC）
 * @param deadlineUtc     该检查点 UTC 截止时刻
 * @param createdAt       记录创建时间（Asia/Shanghai）
 */
public record TransferAnomaly(
        Long id,
        Long transferId,
        String evidenceKey,
        int checkpointSeq,
        AnomalyReason reason,
        String expectedSealNo,
        String submittedSealNo,
        LocalDateTime arrivedAtUtc,
        LocalDateTime deadlineUtc,
        LocalDateTime createdAt) {
}