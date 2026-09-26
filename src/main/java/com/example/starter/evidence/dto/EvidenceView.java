package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 证物视图。
 *
 * @param evidenceKey 证物业务键
 * @param caseKey     当前所属案件键（跨案移交后为目标案件，历史归属见双案保管链）
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param custodianId 当前保管人
 * @param status      证物状态
 * @param location    当前存放位置；null 表示未登记
 * @param sealVersion 封签版本，入库固化为 1，之后不可修改
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
        String location,
        int sealVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
