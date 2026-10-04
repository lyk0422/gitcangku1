package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 检查点签到记录视图。
 *
 * @param transferId    所属交接记录 id
 * @param evidenceKey   关联证物业务键
 * @param checkpointSeq 签到的检查点序号
 * @param sealNo        签到时提交并核对一致的封条编号
 * @param arrivedAtUtc  到达时刻（UTC）
 * @param createdAt     记录创建时间（Asia/Shanghai）
 */
public record SigninView(
        Long transferId,
        String evidenceKey,
        int checkpointSeq,
        String sealNo,
        LocalDateTime arrivedAtUtc,
        LocalDateTime createdAt) {
}