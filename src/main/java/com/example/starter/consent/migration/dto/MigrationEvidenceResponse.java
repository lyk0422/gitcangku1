package com.example.starter.consent.migration.dto;

import java.time.Instant;
import java.util.List;

/**
 * 迁移证据只读视图，按 catalogGeneration 稳定排序。
 *
 * @param migrations 全部已生效迁移的证据（稳定排序）
 */
public record MigrationEvidenceResponse(List<MigrationEvidence> migrations) {

    /**
     * 单条迁移证据。
     *
     * @param migrationKey      迁移键
     * @param catalogVersion    旧目录代次
     * @param catalogGeneration 新目录代次
     * @param sourcePurpose     被拆分旧用途
     * @param sourceRangeStart  旧用途范围起点（左闭，含）
     * @param sourceRangeEnd    旧用途范围终点（右开，不含）
     * @param effectiveStart    生效窗口起点（UTC，左闭）
     * @param effectiveEnd      生效窗口终点（UTC，右开，不含）
     * @param status            状态：APPLIED
     * @param requestId         激活请求标识
     * @param appliedAt         激活时间（UTC）
     * @param targets           新用途映射（按提交序号稳定排序）
     */
    public record MigrationEvidence(String migrationKey,
                                    long catalogVersion,
                                    long catalogGeneration,
                                    String sourcePurpose,
                                    long sourceRangeStart,
                                    long sourceRangeEnd,
                                    Instant effectiveStart,
                                    Instant effectiveEnd,
                                    String status,
                                    String requestId,
                                    Instant appliedAt,
                                    List<TargetView> targets) {
    }

    /**
     * 迁移目标用途视图。
     */
    public record TargetView(String purpose, long rangeStart, long rangeEnd, String supersedes, int ordinal) {
    }
}
