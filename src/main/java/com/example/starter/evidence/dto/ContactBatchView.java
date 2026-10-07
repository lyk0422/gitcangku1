package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 接触批次视图。
 *
 * @param batchKey     接触批次业务键
 * @param eventSeq     接触事件序号（全局递增）
 * @param custodianId  登记时的共同保管人
 * @param evidenceKeys 成员证物业务键（按登记顺序）
 * @param createdAt    登记时刻（UTC）
 */
public record ContactBatchView(
        String batchKey,
        long eventSeq,
        String custodianId,
        List<String> evidenceKeys,
        LocalDateTime createdAt) {
}
