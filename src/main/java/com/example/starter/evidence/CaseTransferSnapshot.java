package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双案封存快照（只追加，不可变）：固化双方案件、原位置、封签版本与令版本。
 *
 * @param id                主键
 * @param transferId        跨案移交批次标识
 * @param evidenceKey       关联证物业务键
 * @param sourceCaseKey     来源案件键（移交时固化）
 * @param targetCaseKey     目标案件键（移交时固化）
 * @param sourceCustodianId 来源案件保管人（移交时固化）
 * @param targetCustodianId 目标案件保管人（移交时固化）
 * @param originLocation    原位置：来源案件键#来源保管人 的固化标识
 * @param sealNo            封签版本（移交时封条编号快照）
 * @param orderVersion      移交令版本（移交时固化）
 * @param createdAt         快照固化时间（UTC）
 */
public record CaseTransferSnapshot(
        long id,
        String transferId,
        String evidenceKey,
        String sourceCaseKey,
        String targetCaseKey,
        String sourceCustodianId,
        String targetCustodianId,
        String originLocation,
        String sealNo,
        String orderVersion,
        LocalDateTime createdAt) {
}
