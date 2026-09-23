package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合包归还批次视图（双人确认 + 逐件不可变快照）。
 *
 * @param batchSeq      包内批次稳定排序序号
 * @param receiverId    接收人
 * @param reviewerId    复核人
 * @param returnedAt    本批归还时刻（UTC）
 * @param closedPackage 本批是否触发组合包自动关闭
 * @param items         本批逐件归还快照（按包内稳定排序）
 */
public record PackageBatchView(
        int batchSeq,
        String receiverId,
        String reviewerId,
        LocalDateTime returnedAt,
        boolean closedPackage,
        List<Item> items) {

    /**
     * 批次内单件归还快照。
     *
     * @param evidenceKey 证物业务键
     * @param sealVersion 归还时核验一致的冻结封条版本
     * @param itemSeq     证物在包内的稳定排序序号
     */
    public record Item(String evidenceKey, long sealVersion, int itemSeq) {
    }
}
