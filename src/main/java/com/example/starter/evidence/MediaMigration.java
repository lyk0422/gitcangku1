package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 介质迁移记录实体，对应 media_migration 表。记录只追加，决定（切换/取消）后状态不再变化。
 * 创建时固定源介质版本与新介质；切换时冻结两介质摘要与见证人。
 *
 * @param id            主键
 * @param evidenceKey   关联证物业务键
 * @param sourceMediaId 固定的源介质（创建迁移时的 ACTIVE 介质）
 * @param targetMediaId 新介质（创建时为 UNVERIFIED）
 * @param status        迁移状态
 * @param createdBy     发起迁移的当前保管人
 * @param witnessId     提交验证摘要的非保管人见证者；null 表示尚未验证
 * @param sourceSha256  切换时冻结的源介质摘要；null 表示尚未切换
 * @param targetSha256  切换时冻结的新介质摘要；null 表示尚未切换
 * @param createdAt     迁移创建时间（Asia/Shanghai）
 * @param decidedAt     切换或取消时间；null 表示仍待完成
 */
public record MediaMigration(
        Long id,
        String evidenceKey,
        Long sourceMediaId,
        Long targetMediaId,
        MigrationStatus status,
        String createdBy,
        String witnessId,
        String sourceSha256,
        String targetSha256,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
