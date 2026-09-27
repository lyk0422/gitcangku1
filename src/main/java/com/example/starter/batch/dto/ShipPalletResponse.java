package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.time.Instant;
import java.util.List;

/**
 * 装运放行响应，携带固化快照（按装载顺序 seq 排列）与当时隔板版本。
 */
public record ShipPalletResponse(
        String palletKey,
        PalletStatus palletStatus,
        int dividerCount,
        int dividerVersion,
        List<SnapshotBatch> snapshot,
        Instant shippedAt
) {

    /**
     * 装运快照中单个批次的冻结视图：批次、类别、分隔位、隔板版本。
     */
    public record SnapshotBatch(
            String batchKey,
            String segregationCategory,
            int slotNo,
            int dividerVersion
    ) {
    }
}
