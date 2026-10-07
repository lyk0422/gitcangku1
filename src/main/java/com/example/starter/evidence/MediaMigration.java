package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 介质迁移记录实体，对应 media_migration 表。记录只追加，决定（切换/取消）后状态不再变化。
 * 创建时固定源介质版本与新介质号；witnessId 验证通过后写入，切换后冻结。
 *
 * @param id            主键
 * @param migrationKey  迁移业务键，全局唯一
 * @param evidenceKey   关联证物业务键
 * @param sourceMediaId 固定源介质主键（创建迁移时的 ACTIVE 介质）
 * @param sourceMediaNo 固定源介质编号
 * @param sourceVersion 创建迁移时固定的源介质版本号
 * @param targetMediaId 新介质主键
 * @param targetMediaNo 新介质编号
 * @param status        迁移状态
 * @param witnessId     验证通过的非保管人见证者；null 表示尚未验证
 * @param version       迁移版本号，每次状态流转递增；写操作须携带期望版本
 * @param createdBy     创建迁移的保管人
 * @param createdAt     迁移创建时间（Asia/Shanghai）
 * @param decidedAt     切换或取消时间（Asia/Shanghai）；null 表示迁移未完结
 */
public record MediaMigration(
        Long id,
        String migrationKey,
        String evidenceKey,
        long sourceMediaId,
        String sourceMediaNo,
        int sourceVersion,
        long targetMediaId,
        String targetMediaNo,
        MigrationStatus status,
        String witnessId,
        int version,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
