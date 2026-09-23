package com.example.starter.batch.dto;

import com.example.starter.batch.HandoffStatus;

import java.time.Instant;
import java.util.List;

/**
 * 移交单响应/证据视图：移交单概要 + 冻结清单及各阶段快照。
 * 未发生阶段的时间与快照字段为 null；items 按冻结顺序（batchKey 字典序）稳定排序。
 */
public record HandoffResponse(
        String manifestKey,
        String sourcePlant,
        String targetPlant,
        HandoffStatus status,
        Instant expectedArrivalAt,
        String receiver,
        Instant createdAt,
        Instant shippedAt,
        Instant receivedAt,
        Instant cancelledAt,
        List<HandoffItemResponse> items
) {

    /**
     * 清单明细视图：创建时冻结的期望版本与祖先链快照，发运/接收阶段补充的快照字段未发生时为 null。
     */
    public record HandoffItemResponse(
            int seq,
            String batchKey,
            int expectedVersion,
            String preStatus,
            String sealNo,
            Integer receivedVersion,
            List<AncestorSnapshot> lineage
    ) {
    }

    /**
     * 创建时冻结的祖先链节点：祖先批次键、当时状态、当时是否已被直接召回。
     */
    public record AncestorSnapshot(
            String batchKey,
            String status,
            boolean recalled
    ) {
    }
}
