package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.ReviewStatus;
import com.example.starter.evidence.WeightStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 证物视图。重量与复核字段仅批量入库非空。
 *
 * @param evidenceKey    证物业务键
 * @param caseKey        所属案件键；批量入库为 NULL
 * @param category       证物类别；批量入库为 NULL
 * @param sealNo         封条编号；批量入库为 NULL
 * @param custodianId    当前保管人
 * @param status         证物状态
 * @param description    证物描述；仅批量入库
 * @param declaredWeight 申报重量（千克）；仅批量入库
 * @param measuredWeight 实测重量（千克）；仅批量入库
 * @param weightStatus   重量核对结果；NULL 表示未核对
 * @param reviewStatus   复核状态：NONE / PENDING / RESOLVED
 * @param intakeKey      所属批量入库批次键；NULL 表示单件入库
 * @param createdAt      入库时间（Asia/Shanghai）
 * @param updatedAt      最近一次变更时间（Asia/Shanghai）
 */
public record EvidenceView(
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        EvidenceStatus status,
        String description,
        BigDecimal declaredWeight,
        BigDecimal measuredWeight,
        WeightStatus weightStatus,
        ReviewStatus reviewStatus,
        String intakeKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
