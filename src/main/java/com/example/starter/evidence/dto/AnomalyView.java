package com.example.starter.evidence.dto;

import com.example.starter.evidence.AnomalyReason;

import java.time.LocalDateTime;

/**
 * 在途异常记录视图。记录后所属交接立即冻结。
 *
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
public record AnomalyView(
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