package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 证物视图。不含合成文本元数据：合成文本仅通过授权阅检副本读取，
 * 避免随证物视图或保管链泄露给未授权操作人。
 *
 * @param evidenceKey 证物业务键
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param digest      原件数字摘要
 * @param custodianId 当前保管人
 * @param status      证物状态
 * @param createdAt   入库时间（Asia/Shanghai）
 * @param updatedAt   最近一次变更时间（Asia/Shanghai）
 */
public record EvidenceView(
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String digest,
        String custodianId,
        EvidenceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}