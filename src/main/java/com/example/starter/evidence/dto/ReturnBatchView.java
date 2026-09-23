package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分批归还批次视图。
 *
 * @param batchId    批次主键
 * @param receiverId 接收人
 * @param reviewerId 复核人
 * @param returnedAt 本批归还时刻（UTC）
 * @param note       批次备注；null 表示未填写
 * @param items      本批逐件保管链（按证物键稳定排序）
 */
public record ReturnBatchView(
        Long batchId,
        String receiverId,
        String reviewerId,
        LocalDateTime returnedAt,
        String note,
        List<ChainItemView> items) {

    /**
     * 批次内逐件保管链视图。
     *
     * @param evidenceKey 证物业务键
     * @param sealVersion 归还核验通过的借出冻结封条版本
     * @param eventAt     逐件保管链事件时间（UTC）
     */
    public record ChainItemView(
            String evidenceKey,
            Long sealVersion,
            LocalDateTime eventAt) {
    }
}
