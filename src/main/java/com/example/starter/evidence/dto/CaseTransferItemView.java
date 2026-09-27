package com.example.starter.evidence.dto;

import com.example.starter.evidence.CaseTransferItemStatus;

import java.time.LocalDateTime;

/**
 * 跨案移交明细（双案封存快照）视图。快照字段为移交时刻固化的业务证据，不随后续操作改写。
 *
 * @param transferId      所属移交业务键
 * @param evidenceKey     证物业务键
 * @param seqNo           批内序号（按证物键规范排序从 1 开始）
 * @param sourceCaseKey   来源案件键（快照固化）
 * @param targetCaseKey   目标案件键（快照固化）
 * @param sourceCustodian 原位置：移交前来源案件保管人（快照固化）
 * @param targetCustodian 移交后目标案件保管人（快照固化）
 * @param sealNo          移交时封条编号（快照固化）
 * @param sealVersion     移交时封签版本（快照固化）
 * @param orderVersion    移交令版本（快照固化）
 * @param status          明细状态
 * @param createdAt       移交时间（UTC）
 * @param revokedAt       撤销时间（UTC）；null 表示未撤销
 */
public record CaseTransferItemView(
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
