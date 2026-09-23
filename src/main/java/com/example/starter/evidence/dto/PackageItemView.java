package com.example.starter.evidence.dto;

import com.example.starter.evidence.PackageItemStatus;

import java.time.LocalDateTime;

/**
 * 组合包逐件借出明细视图。
 *
 * @param evidenceKey  证物业务键
 * @param sealVersion  借出时冻结的封条版本
 * @param itemSeq      包内稳定排序序号
 * @param status       明细状态：OUT 未归还 / RETURNED 已归还
 * @param returnedBatchSeq 归还所在批次序号；null 表示尚未归还
 * @param returnedAt   实际归还时刻（UTC）；null 表示尚未归还
 */
public record PackageItemView(
        String evidenceKey,
        long sealVersion,
        int itemSeq,
        PackageItemStatus status,
        Integer returnedBatchSeq,
        LocalDateTime returnedAt) {
}
