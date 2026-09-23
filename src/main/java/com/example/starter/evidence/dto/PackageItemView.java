package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 组合包证物明细视图。
 *
 * @param evidenceKey       证物业务键
 * @param frozenSealVersion 借出时冻结的封条版本
 * @param returned          是否已归还
 * @param returnBatchId     已归还批次主键；null 表示尚未归还
 * @param returnedAt        实际归还时刻（UTC）；null 表示尚未归还
 */
public record PackageItemView(
        String evidenceKey,
        Long frozenSealVersion,
        boolean returned,
        Long returnBatchId,
        LocalDateTime returnedAt) {
}
