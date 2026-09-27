package com.example.starter.evidence.dto;

import com.example.starter.evidence.CaseTransferStatus;

import java.time.LocalDateTime;

/**
 * 跨案移交明细项视图（规范化排序结果与固化快照字段）。
 *
 * @param seq              规范化排序序号（从 0 开始）
 * @param evidenceKey      证物业务键
 * @param originalLocation 移交时原存放位置；null 表示未登记库位
 * @param sealVersion      移交时封签版本
 */
public record CaseTransferItemView(
        int seq,
        String evidenceKey,
        String originalLocation,
        int sealVersion) {
}
