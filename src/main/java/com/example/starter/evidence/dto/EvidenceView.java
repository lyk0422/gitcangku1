package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 证物视图。
 *
 * @param evidenceKey 证物业务键
 * @param caseKey     当前所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param custodianId 当前保管人
 * @param status      证物状态
 * @param location    原存放位置（库位）；null 表示未登记库位
 * @param sealVersion 封签版本
 * @param createdAt   入库时间（UTC）
 * @param updatedAt   最近一次变更时间（UTC）
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
