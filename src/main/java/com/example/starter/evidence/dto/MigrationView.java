package com.example.starter.evidence.dto;

import com.example.starter.evidence.MigrationStatus;

import java.time.LocalDateTime;

/**
 * 介质迁移视图。
 *
 * @param migrationKey  迁移业务键
 * @param evidenceKey   关联证物业务键
 * @param sourceMediaNo 固定源介质编号
 * @param sourceVersion 创建迁移时固定的源介质版本号
 * @param targetMediaNo 新介质编号
 * @param status        迁移状态
 * @param witnessId     验证通过的见证者；null 表示尚未验证
 * @param version       迁移版本号
 * @param createdBy     创建迁移的保管人
 * @param createdAt     迁移创建时间（Asia/Shanghai）
 * @param decidedAt     切换或取消时间（Asia/Shanghai）；null 表示未完结
 */
public record MigrationView(
        String migrationKey,
        String evidenceKey,
        String sourceMediaNo,
        int sourceVersion,
        String targetMediaNo,
        MigrationStatus status,
        String witnessId,
        int version,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
