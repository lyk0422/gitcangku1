package com.example.starter.pallet.dto;

import java.time.Instant;
import java.util.List;

/**
 * 托盘历史响应：全部装载记录（含已解除、已装运）、装运快照与隔板变更历史。
 * 历史只增不改；字段、排序（按事件顺序）、时间精度与 null 语义稳定。
 */
public record PalletHistoryResponse(
        String palletKey,
        String status,
        int dividerCount,
        int dividerVersion,
        String shippedBy,
        Instant shippedAt,
        String disposedBy,
        Instant disposedAt,
        String disposalReason,
        Instant createdAt,
        List<LoadEntry> loads,
        List<ShipmentEntry> shipment,
        List<DividerChangeEntry> dividerChanges
) {
    /**
     * 装载历史项：unloadedAt/shippedAt 为 null 表示该事件未发生；
     * 在托区间按 [loadedAt, unloadedAt) 左闭右开解释。
     */
    public record LoadEntry(
            String batchKey,
            String category,
            int compartment,
            int seq,
            String status,
            boolean reviewFlag,
            Instant reviewMarkedAt,
            Instant loadedAt,
            Instant unloadedAt,
            Instant shippedAt
    ) {
    }

    /**
     * 装运快照项：固化装运时点的类别、分隔位与隔板版本，不随后续变更改写。
     */
    public record ShipmentEntry(
            String batchKey,
            String category,
            int compartment,
            int seq,
            int dividerCount,
            int dividerVersion,
            Instant shippedAt
    ) {
    }

    /**
     * 隔板变更历史项。
     */
    public record DividerChangeEntry(
            int oldDividerCount,
            int newDividerCount,
            int dividerVersion,
            Instant changedAt
    ) {
    }
}
