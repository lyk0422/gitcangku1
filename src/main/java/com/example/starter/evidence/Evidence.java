package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/caseKey/category 入库后不可修改；custodianId 与 status 随交接、借出、检验流转；
 * sealNo 与 sealVersion 仅在检验完成换新封签时同事务更新，版本只增不减。
 *
 * @param id          主键
 * @param evidenceKey 证物业务键，全局唯一
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      当前封条编号（检验完成后为新封签编号）
 * @param custodianId 当前保管人
 * @param status      证物状态
 * @param sealVersion 当前封签版本：入库为 1，检验完成换新封签时递增 1
 * @param createdAt   入库时间（Asia/Shanghai）
 * @param updatedAt   最近一次变更时间（Asia/Shanghai）
 */
public record Evidence(
        Long id,
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        EvidenceStatus status,
        Integer sealVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
