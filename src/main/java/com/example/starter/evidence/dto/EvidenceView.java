package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 证物视图。
 *
 * @param evidenceKey 证物业务键
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param custodianId 当前保管人
 * @param status      证物状态
 * @param version     证物版本：初始 1，状态写入与合并均推进 1
 * @param aliasOf     合并后指向的最终主号；null 表示非别名
 * @param createdAt   入库时间（Asia/Shanghai）
 * @param updatedAt   最近一次变更时间（Asia/Shanghai）
 */
public record EvidenceView(
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        EvidenceStatus status,
        long version,
        String aliasOf,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
