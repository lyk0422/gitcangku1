package com.example.starter.evidence;

/**
 * 跨案移交证物条目实体，对应 case_transfer_item 表。按 seq_no 固化提交时的规范化排序。
 *
 * @param id          主键
 * @param transferId  所属移交主键（transferId）
 * @param evidenceKey 证物业务键
 * @param seqNo       规范化排序序号，从 0 开始
 */
public record CaseTransferItem(
        Long id,
        Long transferId,
        String evidenceKey,
        int seqNo) {
}
