package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物实体，对应 evidence 表。
 * evidenceKey/caseKey/category/sealNo/location/sealVersion 入库后不可修改；
 * custodianId 与 status 随交接、核验流转；跨案移交在同一事务切换 caseKey 与 custodianId。
 *
 * @param id          主键
 * @param evidenceKey 证物业务键，全局唯一
 * @param caseKey     当前所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param custodianId 当前保管人
 * @param status      证物状态
 * @param location    原存放位置（库位）；null 表示未登记库位
 * @param sealVersion 封签版本，入库为 1，跨案移交双案快照固化
 * @param createdAt   入库时间（UTC）
 * @param updatedAt   最近一次变更时间（UTC）
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
