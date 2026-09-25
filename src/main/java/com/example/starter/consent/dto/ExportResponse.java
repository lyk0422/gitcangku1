package com.example.starter.consent.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 导出快照响应：返回固化的主体、逐用途代次、记录数、按 recordKey 升序的完整记录与生成时刻。
 *
 * @param exportKey  导出快照标识
 * @param subjectKey 主体标识（合成字符串）
 * @param createdAt  快照生成时刻（服务器时区 Asia/Shanghai）
 * @param purposes   逐用途快照内容，按用途名字典序排列
 */
public record ExportResponse(
        String exportKey,
        String subjectKey,
        LocalDateTime createdAt,
        List<PurposeExport> purposes) {

    /**
     * 单用途快照内容。
     *
     * @param purpose     用途
     * @param epoch       生成时刻该用途的有效授权代次
     * @param recordCount 生成时刻该代次的记录总数
     * @param status      读取时该用途当前状态：CURRENT 当前有效代次与快照一致 / STALE 已不一致
     * @param records     固化的完整记录，按 recordKey 升序
     */
    public record PurposeExport(
            Purpose purpose,
            int epoch,
            int recordCount,
            String status,
            List<SnapshotRecord> records) {
    }

    /**
     * 快照内的单条记录。
     *
     * @param recordKey 记录键
     * @param payload   记录内容（生成时刻的快照值）
     */
    public record SnapshotRecord(String recordKey, String payload) {
    }

    /**
     * 用途当前状态：CURRENT 当前有效代次与快照一致；STALE 已撤回或已产生新代次。
     */
    public static final String STATUS_CURRENT = "CURRENT";
    public static final String STATUS_STALE = "STALE";
}
