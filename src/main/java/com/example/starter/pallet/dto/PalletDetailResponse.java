package com.example.starter.pallet.dto;

import java.time.Instant;
import java.util.List;

/**
 * 托盘明细响应：托盘当前状态与全部在托（ACTIVE）装载。
 * 未发生事件的时间与操作人字段为 null，null 语义稳定。
 */
public record PalletDetailResponse(
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
        List<ActiveLoad> activeLoads
) {
    /**
     * 在托装载明细：批次、类别、分隔位、待复核标记与装载时间。
     */
    public record ActiveLoad(
            String batchKey,
            String category,
            int compartment,
            int seq,
            boolean reviewFlag,
            Instant reviewMarkedAt,
            Instant loadedAt
    ) {
    }
}
