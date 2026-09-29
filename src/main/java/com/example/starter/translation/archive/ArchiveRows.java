package com.example.starter.translation.archive;

import java.time.Instant;

/**
 * 归档相关数据库行记录，与 schema.sql 中各表 COMMENT 保持一致。时间字段统一为 UTC。
 */
public final class ArchiveRows {

    private ArchiveRows() {
    }

    /**
     * 发布撤回记录。
     *
     * @param withdrawnAt 撤回时刻，UTC 毫秒精度
     */
    public record WithdrawalRow(long documentId, int publishedVersion, String withdrawnBy,
                                String reason, Instant withdrawnAt) {
    }

    /**
     * 术语版本退役记录。
     *
     * @param retiredAt 退役时刻，UTC 毫秒精度
     */
    public record TermRetirementRow(long documentId, int termVersion, String retiredBy,
                                    String reason, Instant retiredAt) {
    }

    /**
     * 译文归档包行；包体、生成时刻与引用版本不可变，撤销仅更新状态列。
     *
     * @param archiveId      全局唯一归档包 ID
     * @param status         ACTIVE / REVOKED
     * @param createdAt      生成时刻，UTC 毫秒精度，不可变
     * @param revokedBy      撤销操作者；ACTIVE 时为 null
     * @param revokedAt      撤销时刻，UTC 毫秒精度；ACTIVE 时为 null
     * @param revokedReason  撤销原因；ACTIVE 时为 null
     * @param packageJson    归档证据 JSON，生成后只读
     */
    public record ArchivePackageRow(long archiveId, long documentId, int publishedVersion, String status,
                                    String createdBy, Instant createdAt, String revokedBy, Instant revokedAt,
                                    String revokedReason, String packageJson) {
    }
}
