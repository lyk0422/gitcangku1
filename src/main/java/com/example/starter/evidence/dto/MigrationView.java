package com.example.starter.evidence.dto;

import com.example.starter.evidence.MigrationStatus;

import java.time.LocalDateTime;

/**
 * 介质迁移视图。
 *
 * @param evidenceKey   关联证物业务键
 * @param sourceMediaNo 固定的源介质编号
 * @param targetMediaNo 新介质编号
 * @param status        迁移状态（PENDING/COMPLETED/CANCELLED）
 * @param createdBy     发起迁移的保管人
 * @param witnessId     验证见证者；null 表示尚未验证
 * @param sourceSha256  切换时冻结的源介质摘要；null 表示尚未切换
 * @param targetSha256  切换时冻结的新介质摘要；null 表示尚未切换
 * @param createdAt     迁移创建时间（Asia/Shanghai）
 * @param decidedAt     切换或取消时间；null 表示仍待完成
 */
public record MigrationView(
        String evidenceKey,
        String sourceMediaNo,
        String targetMediaNo,
        MigrationStatus status,
        String createdBy,
        String witnessId,
        String sourceSha256,
        String targetSha256,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
