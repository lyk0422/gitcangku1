package com.example.starter.consent.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.consent.Purpose;
import com.example.starter.consent.SnapshotStatus;

/**
 * 导出快照响应：固化主体、逐用途代次、记录数、按 recordKey 升序的完整记录与生成时刻。
 * 明细读取时 status 反映读取当下各用途新鲜度，快照内容本身不变。
 *
 * @param exportKey   导出快照标识
 * @param subjectKey  主体标识（合成字符串）
 * @param generatedAt 快照生成时刻（服务器时区 Asia/Shanghai）
 * @param purposes    逐用途快照，按用途枚举序排列
 */
public record ExportResponse(String exportKey, String subjectKey, LocalDateTime generatedAt,
                             List<PurposeSnapshot> purposes) {

    /**
     * 单用途快照。
     *
     * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
     * @param epoch       快照固化的授权代次
     * @param recordCount 快照内记录数
     * @param status      读取当下新鲜度：CURRENT 与当前有效代次一致 / STALE 已偏离
     * @param records     完整记录，按 recordKey 升序
     */
    public record PurposeSnapshot(Purpose purpose, int epoch, int recordCount,
                                  SnapshotStatus status, List<SnapshotRecord> records) {
    }

    /**
     * 快照内的单条记录。
     *
     * @param recordKey 记录键
     * @param payload   记录内容（合成字符串）
     */
    public record SnapshotRecord(String recordKey, String payload) {
    }
}
