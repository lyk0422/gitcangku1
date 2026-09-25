package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/caseKey/category/sealNo 入库后不可修改；custodianId 与 status 随交接、核验流转。
 * 重量与复核字段仅批量入库填写；单件入库为 NULL（reviewStatus 恒为 NONE）。
 *
 * @param id             主键
 * @param evidenceKey    证物业务键，全局唯一
 * @param caseKey        所属案件键；批量入库为 NULL
 * @param category       证物类别；批量入库为 NULL
 * @param sealNo         封条编号；批量入库为 NULL
 * @param custodianId    当前保管人
 * @param status         证物状态
 * @param description    证物描述；仅批量入库
 * @param declaredWeight 申报重量（千克，两位小数）；仅批量入库
 * @param measuredWeight 实测重量（千克）；仅批量入库
 * @param weightStatus   重量核对结果；NULL 表示未核对（单件入库）
 * @param reviewStatus   复核状态：NONE / PENDING / RESOLVED
 * @param intakeKey      所属批量入库批次键；NULL 表示单件入库
 * @param createdAt      入库时间（Asia/Shanghai）
 * @param updatedAt      最近一次变更时间（Asia/Shanghai）
 */
public record Evidence(
        Long id,
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
