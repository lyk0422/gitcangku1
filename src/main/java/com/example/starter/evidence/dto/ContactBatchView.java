package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 接触批次视图。
 *
 * @param eventSeq     接触事件序号，全局单调递增
 * @param custodianId  登记时的当前保管人
 * @param evidenceKeys 参与接触的证物业务键（按证物键排序）
 * @param createdAt    批次登记时刻（UTC）
 */
public record ContactBatchView(
        long eventSeq,
        String custodianId,
        List<String> evidenceKeys,
        LocalDateTime createdAt) {
}
