package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.SampleKind;

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
 * @param sampleKind  样件类别：STANDARD 普通证物 / ALIQUOT 联合取样子样
 * @param version     证物版本号，状态或保管人每变更一次加 1
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
        SampleKind sampleKind,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
