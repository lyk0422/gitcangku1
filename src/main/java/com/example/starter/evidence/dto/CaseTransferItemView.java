package com.example.starter.evidence.dto;

/**
 * 跨案移交单条证物快照视图（字段固化自双案封存快照）。
 *
 * @param evidenceKey       证物业务键
 * @param sourceCustodianId 来源案件保管人（移交时固化）
 * @param targetCustodianId 目标案件保管人（移交时固化）
 * @param originLocation    原位置：来源案件键#来源保管人
 * @param sealNo            封签版本（移交时封条编号快照）
 */
public record CaseTransferItemView(
        String evidenceKey,
        String sourceCustodianId,
        String targetCustodianId,
        String originLocation,
        String sealNo) {
}
