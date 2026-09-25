package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/caseKey/category/sealNo 及批量入库的描述与重量入库后不可修改；
 * custodianId 与 status 随交接、核验流转，reviewStatus 仅可由复核提交关闭一次。
 *
 * @param id             主键
 * @param evidenceKey    证物业务键，全局唯一
 * @param caseKey        所属案件键（批量入库为批次虚拟案件键）
 * @param category       证物类别
 * @param sealNo         封条编号
 * @param custodianId    当前保管人
 * @param status         证物状态
 * @param intakeKey      批量入库批次键；单件入库为 null
 * @param description    批量入库申报描述；单件入库为 null
 * @param declaredWeight 申报重量，单位千克(kg)；单件入库为 null
 * @param measuredWeight 实测重量，单位千克(kg)；单件入库为 null
 * @param weightCheck    批量重量核对结果；单件入库为 null
 * @param reviewStatus   差异复核状态；MATCHED 与单件入库为 null
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
        String intakeKey,
        String description,
        BigDecimal declaredWeight,
        BigDecimal measuredWeight,
        WeightCheck weightCheck,
        ReviewStatus reviewStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
