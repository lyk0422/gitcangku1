package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/caseKey/category 入库后不可修改；sealNo/sealVersion 仅在检验完成重封时更新；
 * custodianId 与 status 随交接、核验、检验流转。
 *
 * @param id          主键
 * @param evidenceKey 证物业务键，全局唯一
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      当前封条编号（重封后为新编号，旧编号保存在检验保管链事件中）
 * @param sealVersion 当前封签版本，初始 1，每次重封递增 1
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
        int sealVersion,
        String custodianId,
        EvidenceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
