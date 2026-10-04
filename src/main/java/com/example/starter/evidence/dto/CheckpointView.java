package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 受监管交接检查点视图。
 *
 * @param transferId  所属交接记录 id
 * @param evidenceKey 关联证物业务键
 * @param seq         检查点序号
 * @param deadlineUtc 该点 UTC 截止时刻
 * @param createdAt   记录创建时间（Asia/Shanghai）
 */
public record CheckpointView(
        Long transferId,
        String evidenceKey,
        int seq,
        LocalDateTime deadlineUtc,
        LocalDateTime createdAt) {
}