package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 证物视图。
 *
 * @param evidenceKey     证物业务键
 * @param caseKey         所属案件键
 * @param category        证物类别
 * @param sealNo          封条编号
 * @param custodianId     当前保管人
 * @param status          证物状态
 * @param locationCode    当前库位编码；null 表示未分配库位（不在库）
 * @param locationVersion 库位版本号，每次迁移完成 +1
 * @param createdAt       入库时间（Asia/Shanghai）
 * @param updatedAt       最近一次变更时间（Asia/Shanghai）
 */
public record EvidenceView(
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        EvidenceStatus status,
        String locationCode,
        long locationVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
