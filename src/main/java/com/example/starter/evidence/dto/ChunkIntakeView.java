package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 分块入库待接收记录视图。
 *
 * @param intakeKey     分块入库业务键
 * @param evidenceKey   完成接收后写入的证物业务键
 * @param creatorId     创建待接收记录的入库人
 * @param totalBytes    声明的总字节数
 * @param sha256        完整内容的 SHA-256 摘要（十六进制小写）
 * @param status        接收状态：RECEIVING / COMPLETED / ABANDONED
 * @param version       当前记录版本
 * @param receivedBytes 已接收字节数（各块长度之和）
 * @param createdAt     创建时间（Asia/Shanghai）
 * @param decidedAt     完成或放弃时间；null 表示仍在接收中
 */
public record ChunkIntakeView(
        String intakeKey,
        String evidenceKey,
        String creatorId,
        int totalBytes,
        String sha256,
        String status,
        int version,
        int receivedBytes,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
