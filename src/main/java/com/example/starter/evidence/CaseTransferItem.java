package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案移交明细实体，对应 case_transfer_item 表。
 * 证物按 evidence_key 规范化排序后的顺序固化（seq 从 0 开始）。
 *
 * @param id               主键
 * @param transferId       所属跨案移交业务 ID
 * @param evidenceKey      移交的证物业务键
 * @param seq              规范化排序序号（从 0 开始）
 * @param originalLocation 移交时原存放位置快照；null 表示未登记库位
 * @param sealVersion      移交时封签版本（快照）
 * @param createdAt        明细创建时间（UTC）
 */
public record CaseTransferItem(
        Long id,
        String transferId,
        String evidenceKey,
        int seq,
        String originalLocation,
        int sealVersion,
        LocalDateTime createdAt) {
}
