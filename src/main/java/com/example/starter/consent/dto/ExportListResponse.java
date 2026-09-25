package com.example.starter.consent.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 按主体的快照列表响应：只读摘要，不包含记录内容。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param snapshots  该主体的全部快照，按生成时刻与 exportKey 升序
 */
public record ExportListResponse(String subjectKey, List<ExportSummary> snapshots) {

    /**
     * 快照摘要。
     *
     * @param exportKey   导出快照标识
     * @param subjectKey  主体标识（合成字符串）
     * @param generatedAt 快照生成时刻（服务器时区 Asia/Shanghai）
     * @param purposes    逐用途代次与记录数摘要
     */
    public record ExportSummary(String exportKey, String subjectKey, LocalDateTime generatedAt,
                                List<PurposeSummary> purposes) {
    }

    /**
     * 单用途摘要。
     *
     * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
     * @param epoch       快照固化的授权代次
     * @param recordCount 快照内记录数
     */
    public record PurposeSummary(Purpose purpose, int epoch, int recordCount) {
    }
}
