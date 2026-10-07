package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 序号归属查询响应：返回当前持有该序号的叶批及其召回状态。
 * recalled 为 true 表示叶批自身已召回或任一级祖先已召回（该序号当前不可用）；
 * recalledBy 为导致不可用的召回批次业务键（自身或最近召回祖先），未召回时为 null。
 */
public record SerialLookupResponse(
        String productCode,
        int serial,
        String batchKey,
        BatchStatus batchStatus,
        boolean recalled,
        String recalledBy
) {
}
