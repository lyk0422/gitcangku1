package com.example.starter.consent.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 按主体查询的快照列表项：只含摘要，不含完整记录。
 *
 * @param exportKey  导出快照标识
 * @param subjectKey 主体标识（合成字符串）
 * @param createdAt  快照生成时刻（服务器时区 Asia/Shanghai）
 * @param purposes   逐用途摘要，按用途名字典序排列
 */
public record ExportSummaryResponse(
        String exportKey,
        String subjectKey,
        LocalDateTime createdAt,
        List<PurposeSummary> purposes) {

    /**
     * 单用途快照摘要。
     *
     * @param purpose     用途
     * @param epoch       生成时刻该用途的有效授权代次
     * @param recordCount 生成时刻该代次的记录总数
     */
    public record PurposeSummary(Purpose purpose, int epoch, int recordCount) {
    }
}
