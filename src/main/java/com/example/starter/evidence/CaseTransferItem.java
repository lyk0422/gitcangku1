package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案移交明细（双案封存快照）实体，对应 case_transfer_item 表。
 * 同一 transferId 下每证物一行，固化双方案件、原位置、封签版本与令版本；快照字段写入后不可修改。
 *
 * @param id              主键
 * @param transferId      所属移交业务键
 * @param evidenceKey     证物业务键
 * @param seqNo           批内序号，按证物键规范排序从 1 开始
 * @param sourceCaseKey   来源案件键（快照固化）
 * @param targetCaseKey   目标案件键（快照固化）
 * @param sourceCustodian 原位置：移交前来源案件保管人（快照固化）
 * @param targetCustodian 移交后目标案件保管人（快照固化）
 * @param sealNo          移交时封条编号（快照固化）
 * @param sealVersion     移交时封签版本：该证物已有封条核验记录数 + 1（快照固化）
 * @param orderVersion    移交令版本（快照固化）
 * @param status          明细状态：TRANSFERRED 已移交 / REVOKED 已撤销
 * @param createdAt       移交时间（UTC）
 * @param revokedAt       撤销时间（UTC）；null 表示未撤销
 */
public record CaseTransferItem(
        Long id,
        String transferId,
        String evidenceKey,
        int seqNo,
        String sourceCaseKey,
        String targetCaseKey,
        String sourceCustodian,
        String targetCustodian,
        String sealNo,
        int sealVersion,
        String orderVersion,
        CaseTransferItemStatus status,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {
}
