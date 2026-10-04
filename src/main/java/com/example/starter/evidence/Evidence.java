package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/caseKey/category/sealNo/digest/syntheticText 入库后不可修改；
 * custodianId 与 status 随交接、核验流转。
 *
 * @param id            主键
 * @param evidenceKey   证物业务键，全局唯一
 * @param caseKey       所属案件键
 * @param category      证物类别
 * @param sealNo        封条编号
 * @param digest        原件数字摘要（如 SHA-256 十六进制），签发阅检副本时冻结到副本
 * @param syntheticText 原件合成文本元数据；仅通过授权阅检副本读取，不随证物视图对外返回
 * @param custodianId   当前保管人
 * @param status        证物状态
 * @param createdAt     入库时间（Asia/Shanghai）
 * @param updatedAt     最近一次变更时间（Asia/Shanghai）
 */
public record Evidence(
        Long id,
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String digest,
        String syntheticText,
        String custodianId,
        EvidenceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}