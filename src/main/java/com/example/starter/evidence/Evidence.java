package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/category/sealNo 入库后不可修改；caseKey 仅在跨案移交事务内切换，历史链保留原归属；
 * custodianId 与 status 随交接、核验流转。
 *
 * @param id          主键
 * @param evidenceKey 证物业务键，全局唯一
 * @param caseKey     当前所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号（封签版本）
 * @param location    证物库位；null 表示未登记库位
 * @param custodianId 当前保管人
 * @param status      证物状态
 * @param createdAt   入库时间（Asia/Shanghai）
 * @param updatedAt   最近一次变更时间（Asia/Shanghai）
 */
public record Evidence(
        Long id,
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String location,
        String custodianId,
        EvidenceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
