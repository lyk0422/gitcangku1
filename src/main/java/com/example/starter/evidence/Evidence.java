package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/category/sealNo/sealVersion 入库后不可修改；
 * caseKey 仅随跨案移交原子切换（原始归属由 custody_case_link 链固化）；
 * custodianId 与 status 随交接、跨案移交流转。
 *
 * @param id          主键
 * @param evidenceKey 证物业务键，全局唯一
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
public record Evidence(
        Long id,
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
